#!/usr/bin/env python3
"""Анализирует WAV-файлы и модель классификатора "Стоп Энурез".

Скрипт воспроизводит извлечение MFCC-признаков из Android-приложения, поэтому
вероятность и вклад признаков соответствуют решению на телефоне с точностью до
погрешности вычислений с плавающей точкой. Аудиозаписи не изменяются.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import wave
from dataclasses import dataclass
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np


COEFFICIENT_COUNT = 13
MEL_FILTER_COUNT = 26
CLASSIFICATION_THRESHOLD = 0.5
MODEL_FEATURE_TYPE_V1 = "mfcc_mean_std"
MODEL_FEATURE_TYPE_V2 = "mfcc_mean_std_without_c0"
TARGET_RMS = 0.1
FILENAME_RE = re.compile(r"(?P<timestamp>\d{4}\.\d{2}\.\d{2}_\d{2}\.\d{2}\.\d{2})_(?P<label>[01])(?P<unchecked>_unchecked)?\.wav$")


@dataclass(frozen=True)
class Sample:
    path: Path
    timestamp: str
    label: int
    unchecked: bool
    features: np.ndarray
    probability: float
    envelope: float
    contributions: np.ndarray


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True, help="Файл stopenuresis_model.json")
    parser.add_argument("--samples", type=Path, required=True, help="Каталог с WAV-файлами")
    parser.add_argument("--output", type=Path, required=True, help="Каталог результатов")
    parser.add_argument("--target-date", help="Дата неразобранных файлов в формате YYYY.MM.DD")
    return parser.parse_args()


def read_wav(path: Path) -> tuple[int, np.ndarray]:
    with wave.open(str(path), "rb") as source:
        if source.getnchannels() != 1 or source.getsampwidth() != 2:
            raise ValueError(f"{path.name}: ожидается одноканальный PCM 16-bit WAV")
        samples = np.frombuffer(source.readframes(source.getnframes()), dtype="<i2")
        return source.getframerate(), samples.astype(np.float64) / np.iinfo(np.int16).max


def mel_filters(sample_rate: int, fft_size: int) -> np.ndarray:
    spectrum_size = fft_size // 2 + 1
    low_mel = hz_to_mel(0.0)
    high_mel = hz_to_mel(sample_rate / 2.0)
    mel_points = np.linspace(low_mel, high_mel, MEL_FILTER_COUNT + 2)
    bins = np.floor((fft_size + 1) * mel_to_hz(mel_points) / sample_rate).astype(int)
    bins = np.clip(bins, 0, spectrum_size - 1)
    filters = np.zeros((MEL_FILTER_COUNT, spectrum_size))
    for index in range(MEL_FILTER_COUNT):
        left, center, right = bins[index : index + 3]
        if center > left:
            filters[index, left:center] = (np.arange(left, center) - left) / (center - left)
        if right > center:
            filters[index, center:right] = (right - np.arange(center, right)) / (right - center)
    return filters


def hz_to_mel(hz: np.ndarray | float) -> np.ndarray | float:
    return 2595.0 * np.log10(1.0 + np.asarray(hz) / 700.0)


def mel_to_hz(mel: np.ndarray) -> np.ndarray:
    return 700.0 * (10.0 ** (mel / 2595.0) - 1.0)


def mfcc_features(sample_rate: int, samples: np.ndarray, feature_type: str) -> np.ndarray:
    if feature_type == MODEL_FEATURE_TYPE_V2:
        samples = normalize_loudness(samples)
        coefficient_indexes = np.arange(1, COEFFICIENT_COUNT)
    elif feature_type == MODEL_FEATURE_TYPE_V1:
        coefficient_indexes = np.arange(COEFFICIENT_COUNT)
    else:
        raise ValueError(f"Неподдерживаемый тип признаков: {feature_type}")
    frame_size = max(int(sample_rate * 0.025), 256)
    hop_size = max(int(sample_rate * 0.010), 80)
    fft_size = 1 << (frame_size - 1).bit_length()
    window = np.hamming(frame_size)
    filters = mel_filters(sample_rate, fft_size)
    filter_indexes = np.arange(MEL_FILTER_COUNT) + 0.5
    if len(samples) < frame_size:
        frames = np.zeros((1, frame_size))
        frames[0, : len(samples)] = samples
    else:
        frames = np.lib.stride_tricks.sliding_window_view(samples, frame_size)[::hop_size]
    spectrum = np.abs(np.fft.rfft(frames * window, n=fft_size, axis=1)) ** 2
    log_mel = np.log(np.maximum(spectrum @ filters.T, 1e-12))
    dct = np.cos(np.pi * coefficient_indexes[:, None] * filter_indexes / MEL_FILTER_COUNT)
    coefficients = log_mel @ dct.T
    return np.concatenate((coefficients.mean(axis=0), coefficients.std(axis=0)))


def normalize_loudness(samples: np.ndarray) -> np.ndarray:
    rms = float(np.sqrt(np.mean(samples * samples))) if len(samples) else 0.0
    if rms < 1e-6:
        return samples
    return samples * (TARGET_RMS / rms)


def average_smoothed_absolute(samples: np.ndarray, sample_rate: int) -> float:
    """Повторяет среднюю сглаженную огибающую, достаточную для сравнения файлов."""
    pcm = np.abs(samples * np.iinfo(np.int16).max)
    window_size = max(sample_rate // 100, 1)
    kernel = np.ones(window_size) / window_size
    return float(np.convolve(pcm, kernel, mode="same").mean())


def sigmoid(value: float) -> float:
    if value >= 40.0:
        return 1.0
    if value <= -40.0:
        return 0.0
    return 1.0 / (1.0 + np.exp(-value))


def load_samples(model: dict[str, object], samples_dir: Path) -> list[Sample]:
    weights = np.asarray(model["weights"], dtype=float)
    means = np.asarray(model["featureMeans"], dtype=float)
    std_devs = np.asarray(model["featureStdDevs"], dtype=float)
    bias = float(model["bias"])
    feature_type = str(model.get("featureType", MODEL_FEATURE_TYPE_V1))
    samples: list[Sample] = []
    for path in sorted(samples_dir.glob("*.wav")):
        match = FILENAME_RE.fullmatch(path.name)
        if not match:
            continue
        sample_rate, audio = read_wav(path)
        features = mfcc_features(sample_rate, audio, feature_type)
        if len(features) != len(weights):
            raise ValueError(f"{path.name}: размер признаков не совпадает с моделью")
        normalized = (features - means) / std_devs
        contributions = weights * normalized
        probability = sigmoid(bias + contributions.sum())
        samples.append(
            Sample(
                path=path,
                timestamp=match.group("timestamp"),
                label=int(match.group("label")),
                unchecked=match.group("unchecked") is not None,
                features=features,
                probability=probability,
                envelope=average_smoothed_absolute(audio, sample_rate),
                contributions=contributions,
            )
        )
    return samples


def label_name(sample: Sample) -> str:
    if sample.unchecked:
        return "неразобранный"
    return "ёрзает" if sample.label else "тишина"


def nearest_labeled(
    sample: Sample,
    labeled: list[Sample],
    std_devs: np.ndarray,
) -> tuple[Sample, float]:
    distances = [float(np.linalg.norm((sample.features - other.features) / std_devs)) for other in labeled]
    index = int(np.argmin(distances))
    return labeled[index], distances[index]


def write_table(samples: list[Sample], model: dict[str, object], output: Path) -> None:
    std_devs = np.asarray(model["featureStdDevs"], dtype=float)
    labeled = [sample for sample in samples if not sample.unchecked]
    with (output / "samples.csv").open("w", newline="", encoding="utf-8") as destination:
        writer = csv.DictWriter(
            destination,
            fieldnames=("file", "timestamp", "type", "probability", "decision", "envelope", "nearest_labeled", "nearest_type", "distance"),
        )
        writer.writeheader()
        for sample in samples:
            nearest, distance = nearest_labeled(sample, labeled, std_devs)
            writer.writerow({
                "file": sample.path.name,
                "timestamp": sample.timestamp,
                "type": label_name(sample),
                "probability": f"{sample.probability:.9f}",
                "decision": "ёрзает" if sample.probability >= CLASSIFICATION_THRESHOLD else "тишина",
                "envelope": f"{sample.envelope:.3f}",
                "nearest_labeled": nearest.path.name,
                "nearest_type": label_name(nearest),
                "distance": f"{distance:.4f}",
            })


def plot(samples: list[Sample], output: Path) -> None:
    labels = [sample for sample in samples if not sample.unchecked]
    unchecked = [sample for sample in samples if sample.unchecked]
    fig, axes = plt.subplots(1, 2, figsize=(14, 5), constrained_layout=True)
    for class_label, name, color in ((0, "тишина", "#4c78a8"), (1, "ёрзает", "#f58518")):
        group = [sample for sample in labels if sample.label == class_label]
        axes[0].scatter([sample.envelope for sample in group], [sample.probability for sample in group], label=name, alpha=0.6, color=color)
    axes[0].scatter([sample.envelope for sample in unchecked], [sample.probability for sample in unchecked], label="неразобранные", marker="x", s=75, color="#e45756")
    axes[0].axhline(CLASSIFICATION_THRESHOLD, color="black", linewidth=1, linestyle="--")
    axes[0].set(xlabel="Средняя сглаженная огибающая", ylabel="Вероятность класса 'ёрзает'", title="Решение модели и громкость")
    axes[0].legend()

    features = np.stack([sample.features for sample in labels])
    correlation = np.corrcoef(features, rowvar=False)
    image = axes[1].imshow(correlation, vmin=-1, vmax=1, cmap="coolwarm")
    axes[1].set(
        title=f"Корреляция {features.shape[1]} MFCC-признаков",
        xlabel="Номер признака",
        ylabel="Номер признака",
    )
    fig.colorbar(image, ax=axes[1], label="Корреляция")
    fig.savefig(output / "overview.png", dpi=160)
    plt.close(fig)


def write_target_report(samples: list[Sample], model: dict[str, object], target_date: str | None, output: Path) -> None:
    target = [sample for sample in samples if sample.unchecked and (target_date is None or sample.timestamp.startswith(target_date))]
    weights = np.asarray(model["weights"], dtype=float)
    report: dict[str, object] = {
        "model_accuracy_saved_by_app": model.get("accuracy"),
        "classification_threshold": CLASSIFICATION_THRESHOLD,
        "unchecked_target_count": len(target),
        "unchecked_positive_count": int(
            sum(sample.probability >= CLASSIFICATION_THRESHOLD for sample in target)
        ),
        "files": [],
    }
    for sample in target:
        top_indexes = np.argsort(np.abs(sample.contributions))[::-1][:5]
        report["files"].append({
            "file": sample.path.name,
            "probability": sample.probability,
            "decision": "ёрзает" if sample.probability >= CLASSIFICATION_THRESHOLD else "тишина",
            "envelope": sample.envelope,
            "largest_feature_contributions": [
                {"feature": int(index), "weight": float(weights[index]), "contribution": float(sample.contributions[index])}
                for index in top_indexes
            ],
        })
    (output / "target_report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    args = parse_args()
    model = json.loads(args.model.read_text(encoding="utf-8"))
    args.output.mkdir(parents=True, exist_ok=True)
    samples = load_samples(model, args.samples)
    if not samples:
        raise SystemExit("В указанном каталоге нет WAV-файлов приложения")
    write_table(samples, model, args.output)
    plot(samples, args.output)
    write_target_report(samples, model, args.target_date, args.output)
    print(f"Проанализировано файлов: {len(samples)}")
    print(f"Результаты: {args.output}")


if __name__ == "__main__":
    main()
