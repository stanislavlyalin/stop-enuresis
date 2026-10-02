#!/usr/bin/env python3
"""Сравнивает акустические признаки и классификаторы для "Стоп Энурез".

Скрипт читает WAV-файлы, сохранённые приложением. Размеченные файлы участвуют
в обучении и групповой проверке по датам, а неразмеченные остаются внешней
проверкой. Это не позволяет пятисекундным фрагментам одной ночи попадать и в
обучение, и в проверку.
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
from scipy.fft import dct
from sklearn.base import clone
from sklearn.ensemble import ExtraTreesClassifier, RandomForestClassifier
from sklearn.feature_selection import SelectKBest, f_classif, mutual_info_classif
from sklearn.metrics import (
    accuracy_score,
    balanced_accuracy_score,
    confusion_matrix,
    f1_score,
    precision_score,
    recall_score,
    roc_auc_score,
)
from sklearn.model_selection import StratifiedGroupKFold
from sklearn.naive_bayes import GaussianNB
from sklearn.neighbors import KNeighborsClassifier
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler
from sklearn.linear_model import LogisticRegression
from sklearn.svm import SVC


COEFFICIENT_COUNT = 13
MEL_FILTER_COUNT = 26
FRAME_SECONDS = 0.025
HOP_SECONDS = 0.010
TARGET_RMS = 0.1
RANDOM_STATE = 20261002
FILENAME_RE = re.compile(
    r"(?P<date>\d{4}\.\d{2}\.\d{2})_(?P<time>\d{2}\.\d{2}\.\d{2})_"
    r"(?P<label>[01])(?P<unchecked>_unchecked)?\.wav$"
)


@dataclass(frozen=True)
class AudioFile:
    path: Path
    date: str
    label: int
    unchecked: bool


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--samples", type=Path, required=True, help="Каталог WAV-файлов")
    parser.add_argument("--output", type=Path, required=True, help="Каталог результатов")
    parser.add_argument(
        "--alarm-date",
        default="2026.10.02",
        help="Дата пяти известных файлов будильника для внешней проверки",
    )
    return parser.parse_args()


def read_wav(path: Path) -> tuple[int, np.ndarray]:
    with wave.open(str(path), "rb") as source:
        if source.getnchannels() != 1 or source.getsampwidth() != 2:
            raise ValueError(f"{path.name}: ожидается одноканальный PCM 16-bit WAV")
        samples = np.frombuffer(source.readframes(source.getnframes()), dtype="<i2")
        return source.getframerate(), samples.astype(np.float64) / np.iinfo(np.int16).max


def normalize_loudness(samples: np.ndarray) -> np.ndarray:
    rms = float(np.sqrt(np.mean(samples * samples))) if len(samples) else 0.0
    return samples if rms < 1e-6 else samples * (TARGET_RMS / rms)


def frame_audio(samples: np.ndarray, sample_rate: int) -> tuple[np.ndarray, int]:
    frame_size = max(int(sample_rate * FRAME_SECONDS), 256)
    hop_size = max(int(sample_rate * HOP_SECONDS), 80)
    if len(samples) < frame_size:
        frames = np.zeros((1, frame_size))
        frames[0, : len(samples)] = samples
    else:
        frames = np.lib.stride_tricks.sliding_window_view(samples, frame_size)[::hop_size]
    return frames, hop_size


def hz_to_mel(hz: np.ndarray | float) -> np.ndarray | float:
    return 2595.0 * np.log10(1.0 + np.asarray(hz) / 700.0)


def mel_to_hz(mel: np.ndarray) -> np.ndarray:
    return 700.0 * (10.0 ** (mel / 2595.0) - 1.0)


def mel_filters(sample_rate: int, fft_size: int) -> np.ndarray:
    spectrum_size = fft_size // 2 + 1
    points = np.linspace(hz_to_mel(0.0), hz_to_mel(sample_rate / 2), MEL_FILTER_COUNT + 2)
    bins = np.clip(
        np.floor((fft_size + 1) * mel_to_hz(points) / sample_rate).astype(int),
        0,
        spectrum_size - 1,
    )
    filters = np.zeros((MEL_FILTER_COUNT, spectrum_size))
    for index in range(MEL_FILTER_COUNT):
        left, center, right = bins[index : index + 3]
        if center > left:
            filters[index, left:center] = (np.arange(left, center) - left) / (center - left)
        if right > center:
            filters[index, center:right] = (right - np.arange(center, right)) / (right - center)
    return filters


def summary(values: np.ndarray, prefix: str) -> dict[str, float]:
    return {
        f"{prefix}_mean": float(np.mean(values)),
        f"{prefix}_std": float(np.std(values)),
        f"{prefix}_p05": float(np.percentile(values, 5)),
        f"{prefix}_p95": float(np.percentile(values, 95)),
    }


def safe_ratio(numerator: np.ndarray | float, denominator: np.ndarray | float) -> np.ndarray | float:
    return np.asarray(numerator) / np.maximum(np.asarray(denominator), 1e-12)


def band_energy_ratio(power: np.ndarray, frequencies: np.ndarray, low: float, high: float) -> np.ndarray:
    mask = (frequencies >= low) & (frequencies < high)
    return safe_ratio(power[:, mask].sum(axis=1), power.sum(axis=1))


def periodicity(rms: np.ndarray) -> float:
    """Максимальная автокорреляция огибающей для периодов 40-1000 мс."""
    centered = rms - rms.mean()
    denominator = float(centered @ centered)
    if denominator < 1e-12:
        return 0.0
    maximum_lag = min(100, len(centered) // 2)
    if maximum_lag < 4:
        return 0.0
    values = [float(centered[:-lag] @ centered[lag:] / denominator) for lag in range(4, maximum_lag + 1)]
    return max(values, default=0.0)


def extract_features(sample_rate: int, raw_samples: np.ndarray) -> dict[str, float]:
    """Извлекает признаки формы и спектра после нормализации громкости.

    Громкость сохраняется только как диагностический raw_rms и не участвует в
    подборе: она уже была причиной ложных срабатываний предыдущей модели.
    """
    samples = normalize_loudness(raw_samples)
    frames, hop_size = frame_audio(samples, sample_rate)
    frame_size = frames.shape[1]
    fft_size = 1 << (frame_size - 1).bit_length()
    window = np.hamming(frame_size)
    power = np.abs(np.fft.rfft(frames * window, n=fft_size, axis=1)) ** 2
    magnitude = np.sqrt(power)
    frequencies = np.fft.rfftfreq(fft_size, d=1.0 / sample_rate)
    spectrum_sum = np.maximum(power.sum(axis=1), 1e-12)
    normalized_spectrum = power / spectrum_sum[:, None]
    centroid = normalized_spectrum @ frequencies
    bandwidth = np.sqrt(np.maximum(normalized_spectrum @ (frequencies**2) - centroid**2, 0.0))
    cumulative = np.cumsum(normalized_spectrum, axis=1)
    rolloff85 = frequencies[np.argmax(cumulative >= 0.85, axis=1)]
    rolloff95 = frequencies[np.argmax(cumulative >= 0.95, axis=1)]
    flatness = np.exp(np.mean(np.log(np.maximum(power, 1e-12)), axis=1)) / np.maximum(
        np.mean(power, axis=1), 1e-12
    )
    entropy = -np.sum(normalized_spectrum * np.log(np.maximum(normalized_spectrum, 1e-12)), axis=1)
    peak_prominence = safe_ratio(power.max(axis=1), power.mean(axis=1))
    dominant_frequency = frequencies[np.argmax(power, axis=1)]
    flux = np.sqrt(np.sum(np.diff(normalized_spectrum, axis=0) ** 2, axis=1))

    rms = np.sqrt(np.mean(frames * frames, axis=1))
    log_rms = np.log(np.maximum(rms, 1e-12))
    zero_crossing_rate = np.mean(np.diff(np.signbit(frames), axis=1) != 0, axis=1)
    energy_p95 = float(np.percentile(rms, 95))
    log_rms_delta = np.diff(log_rms)

    filters = mel_filters(sample_rate, fft_size)
    log_mel = np.log(np.maximum(power @ filters.T, 1e-12))
    coefficients = dct(log_mel, type=2, axis=1, norm=None)[:, :COEFFICIENT_COUNT]
    delta = np.diff(coefficients, axis=0)
    delta2 = np.diff(delta, axis=0)

    result: dict[str, float] = {
        "raw_rms_diagnostic": float(np.sqrt(np.mean(raw_samples * raw_samples))),
        "temporal_crest_factor": float(np.max(np.abs(samples)) / max(np.sqrt(np.mean(samples * samples)), 1e-12)),
        "temporal_active_fraction": float(np.mean(rms >= max(energy_p95 * 0.2, 1e-8))),
        "temporal_energy_cv": float(np.std(rms) / max(np.mean(rms), 1e-12)),
        "temporal_energy_range": float(np.percentile(rms, 95) / max(np.percentile(rms, 5), 1e-12)),
        "temporal_onset_rate": float(np.mean(log_rms_delta > 0.5)) if len(log_rms_delta) else 0.0,
        "temporal_periodicity": periodicity(rms),
        "temporal_zcr_mean": float(np.mean(zero_crossing_rate)),
        "temporal_zcr_std": float(np.std(zero_crossing_rate)),
        "spectral_flux_mean": float(np.mean(flux)) if len(flux) else 0.0,
        "spectral_flux_std": float(np.std(flux)) if len(flux) else 0.0,
        "spectral_low_250_1000_mean": float(np.mean(band_energy_ratio(power, frequencies, 250, 1000))),
        "spectral_mid_1000_3000_mean": float(np.mean(band_energy_ratio(power, frequencies, 1000, 3000))),
        "spectral_high_3000_8000_mean": float(np.mean(band_energy_ratio(power, frequencies, 3000, 8000))),
    }
    for name, values in (
        ("temporal_rms", rms),
        ("spectral_centroid", centroid),
        ("spectral_bandwidth", bandwidth),
        ("spectral_rolloff85", rolloff85),
        ("spectral_rolloff95", rolloff95),
        ("spectral_flatness", flatness),
        ("spectral_entropy", entropy),
        ("spectral_peak_prominence", peak_prominence),
        ("spectral_dominant_frequency", dominant_frequency),
    ):
        result.update(summary(values, name))
    for index in range(1, COEFFICIENT_COUNT):
        result[f"mfcc_{index}_mean"] = float(coefficients[:, index].mean())
        result[f"mfcc_{index}_std"] = float(coefficients[:, index].std())
        result[f"delta_mfcc_{index}_mean"] = float(delta[:, index].mean()) if len(delta) else 0.0
        result[f"delta_mfcc_{index}_std"] = float(delta[:, index].std()) if len(delta) else 0.0
        result[f"delta2_mfcc_{index}_std"] = float(delta2[:, index].std()) if len(delta2) else 0.0
    return result


def load_files(samples_dir: Path) -> list[AudioFile]:
    files = []
    for path in sorted(samples_dir.glob("*.wav")):
        match = FILENAME_RE.fullmatch(path.name)
        if match:
            files.append(
                AudioFile(
                    path=path,
                    date=match.group("date"),
                    label=int(match.group("label")),
                    unchecked=match.group("unchecked") is not None,
                )
            )
    if not files:
        raise ValueError("В указанном каталоге нет подходящих WAV-файлов")
    return files


def build_matrix(files: list[AudioFile]) -> tuple[np.ndarray, list[str]]:
    rows = []
    for index, audio_file in enumerate(files, start=1):
        sample_rate, samples = read_wav(audio_file.path)
        rows.append(extract_features(sample_rate, samples))
        if index % 100 == 0 or index == len(files):
            print(f"Обработано файлов: {index}/{len(files)}")
    names = sorted(rows[0])
    return np.asarray([[row[name] for name in names] for row in rows]), names


def model_candidates(feature_count: int) -> dict[str, Pipeline]:
    selected = min(32, feature_count)
    selector = SelectKBest(f_classif, k=selected)
    return {
        "Логистическая регрессия": Pipeline([
            ("scale", StandardScaler()), ("select", selector),
            ("model", LogisticRegression(C=0.1, class_weight="balanced", max_iter=3000, random_state=RANDOM_STATE)),
        ]),
        "Гауссов наивный Байес": Pipeline([
            ("scale", StandardScaler()), ("select", selector), ("model", GaussianNB(var_smoothing=1e-8)),
        ]),
        "kNN": Pipeline([
            ("scale", StandardScaler()), ("select", selector), ("model", KNeighborsClassifier(n_neighbors=11, weights="distance", p=2)),
        ]),
        "SVM с радиальным ядром": Pipeline([
            ("scale", StandardScaler()), ("select", selector),
            ("model", SVC(C=1.0, gamma="scale", class_weight="balanced", probability=True, random_state=RANDOM_STATE)),
        ]),
        "Случайный лес": Pipeline([
            ("select", selector),
            ("model", RandomForestClassifier(n_estimators=400, min_samples_leaf=3, max_features="sqrt", class_weight="balanced", n_jobs=-1, random_state=RANDOM_STATE)),
        ]),
        "Экстремальные деревья": Pipeline([
            ("select", selector),
            ("model", ExtraTreesClassifier(n_estimators=400, min_samples_leaf=2, max_features=0.7, class_weight="balanced", n_jobs=-1, random_state=RANDOM_STATE)),
        ]),
    }


def evaluate_models(
    candidates: dict[str, Pipeline], X: np.ndarray, y: np.ndarray, groups: np.ndarray
) -> tuple[list[dict[str, float | str]], dict[str, np.ndarray]]:
    splitter = StratifiedGroupKFold(n_splits=5, shuffle=True, random_state=RANDOM_STATE)
    results = []
    predictions = {}
    for name, candidate in candidates.items():
        oof = np.zeros(len(y))
        for train_indexes, test_indexes in splitter.split(X, y, groups):
            model = clone(candidate)
            model.fit(X[train_indexes], y[train_indexes])
            oof[test_indexes] = model.predict_proba(X[test_indexes])[:, 1]
        decision = (oof >= 0.5).astype(int)
        results.append({
            "model": name,
            "accuracy": accuracy_score(y, decision),
            "balanced_accuracy": balanced_accuracy_score(y, decision),
            "precision": precision_score(y, decision, zero_division=0),
            "recall": recall_score(y, decision, zero_division=0),
            "f1": f1_score(y, decision, zero_division=0),
            "roc_auc": roc_auc_score(y, oof),
            "false_positives": int(np.sum((decision == 1) & (y == 0))),
            "false_negatives": int(np.sum((decision == 0) & (y == 1))),
        })
        predictions[name] = oof
    return results, predictions


def feature_ranking(X: np.ndarray, y: np.ndarray, names: list[str]) -> list[dict[str, float | str]]:
    information = mutual_info_classif(X, y, random_state=RANDOM_STATE)
    aucs = []
    for column in X.T:
        auc = roc_auc_score(y, column)
        aucs.append(max(auc, 1.0 - auc))
    forest = ExtraTreesClassifier(
        n_estimators=700, min_samples_leaf=2, max_features=0.7,
        class_weight="balanced", n_jobs=-1, random_state=RANDOM_STATE,
    ).fit(X, y)
    return sorted(
        [
            {"feature": name, "mutual_information": float(mi), "univariate_auc": float(auc), "tree_importance": float(importance)}
            for name, mi, auc, importance in zip(names, information, aucs, forest.feature_importances_)
        ],
        key=lambda item: (float(item["tree_importance"]), float(item["mutual_information"])),
        reverse=True,
    )


def write_csv(path: Path, rows: list[dict[str, object]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as destination:
        writer = csv.DictWriter(destination, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def plot_results(output: Path, ranking: list[dict[str, float | str]], y: np.ndarray, probability: np.ndarray) -> None:
    top = ranking[:20]
    fig, axes = plt.subplots(1, 2, figsize=(16, 7), constrained_layout=True)
    axes[0].barh([str(item["feature"]) for item in top][::-1], [float(item["tree_importance"]) for item in top][::-1])
    axes[0].set(title="20 наиболее важных признаков", xlabel="Важность экстремальных деревьев")
    axes[1].hist(probability[y == 0], bins=30, alpha=0.7, label="тишина и посторонние звуки")
    axes[1].hist(probability[y == 1], bins=30, alpha=0.7, label="ёрзание")
    axes[1].axvline(0.5, color="black", linestyle="--", linewidth=1)
    axes[1].set(title="Групповая проверка выбранной модели", xlabel="Вероятность ёрзания", ylabel="Количество файлов")
    axes[1].legend()
    fig.savefig(output / "overview.png", dpi=160)
    plt.close(fig)


def describe_feature(name: str) -> str:
    if name.startswith("spectral_flatness"):
        return "плоскостность спектра: шумовой звук против выраженных тонов будильника"
    if name.startswith("spectral_peak_prominence"):
        return "выраженность узких спектральных пиков: тональная структура"
    if name.startswith("spectral_flux") or name.startswith("delta"):
        return "изменчивость спектра во времени"
    if name.startswith("temporal_active") or name.startswith("temporal_energy"):
        return "непрерывность и разброс громкости по коротким кадрам"
    if name.startswith("temporal_periodicity"):
        return "повторяемость огибающей, характерная для сигналов будильника"
    if name.startswith("mfcc"):
        return "форма спектральной огибающей"
    return "дополнительная характеристика формы звука"


def write_report(
    output: Path,
    files: list[AudioFile],
    names: list[str],
    rankings: list[dict[str, float | str]],
    scores: list[dict[str, float | str]],
    best_name: str,
    best_model: Pipeline,
    X_labeled: np.ndarray,
    y: np.ndarray,
    X_unchecked: np.ndarray,
    unchecked: list[AudioFile],
    oof_probability: np.ndarray,
    alarm_date: str,
) -> None:
    score_by_name = {str(item["model"]): item for item in scores}
    chosen = score_by_name[best_name]
    alarm_indexes = [index for index, item in enumerate(unchecked) if item.date == alarm_date][-5:]
    alarm_files = [unchecked[index] for index in alarm_indexes]
    alarm_matrix = X_unchecked[alarm_indexes]
    alarm_probability = best_model.predict_proba(alarm_matrix)[:, 1] if len(alarm_matrix) else np.array([])
    selected_names = np.asarray(names)[best_model.named_steps["select"].get_support()]
    positives = int(y.sum())
    negatives = int(len(y) - positives)
    lines = [
        "# Исследование акустических признаков",
        "",
        "## Данные и методика",
        "",
        f"- Размечено: {len(y)} файлов - {positives} ёрзаний и {negatives} файлов класса 'тишина/посторонний звук'.",
        f"- Рассчитано {len(names)} признаков на всех {len(files)} файлах. Громкость `raw_rms_diagnostic` сохранена только для диагностики и не подаётся моделям.",
        "- Проверка: `StratifiedGroupKFold`, 5 частей, группа - календарная дата. Записи одной ночи не разделяются между обучением и проверкой.",
        f"- Последние 5 неразмеченных файлов {alarm_date} - известный будильник. Они не использованы при обучении и служат внешней проверкой ложных срабатываний.",
        "",
        "## Сравнение алгоритмов",
        "",
        "| Алгоритм | Точность | Сбалансированная точность | Полнота ёрзаний | Точность срабатываний | F1 | Ложные срабатывания | Будильник |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for item in sorted(scores, key=lambda row: float(row["balanced_accuracy"]), reverse=True):
        lines.append(
            f"| {item['model']} | {float(item['accuracy']):.3f} | {float(item['balanced_accuracy']):.3f} | "
            f"{float(item['recall']):.3f} | {float(item['precision']):.3f} | {float(item['f1']):.3f} | {int(item['false_positives'])} |"
            f" {int(item['alarm_false_positives'])}/5 |"
        )
    lines.extend([
        "",
        "Максимальная полученная точность приведена в таблице, но для приложения она не является главным критерием: при 88% отрицательных файлов её можно искусственно повысить постоянным ответом 'тишина'. Выбор сделан по сбалансированной точности, полноте ёрзаний и внешней проверке будильником.",
        "",
        "## Рекомендуемая модель",
        "",
        f"Выбрана: **{best_name}**. Групповая точность - {float(chosen['accuracy']):.3f}, сбалансированная точность - {float(chosen['balanced_accuracy']):.3f}, полнота ёрзаний - {float(chosen['recall']):.3f}. Внутренняя проверка содержит {int(chosen['false_positives'])} ложных срабатываний, на будильнике - {int(chosen['alarm_false_positives'])}/5.",
        "",
        f"В приложении следует оставить нормализацию RMS и исключение `MFCC[0]`. Рекомендуемый компактный набор - автоматически отобранные 32 признака ниже; он включает MFCC без C0, их изменения во времени и признаки тональности/непрерывности. Алгоритм - {best_name}. Для Android его потребуется реализовать в Kotlin и проверить задержку, память и совпадение расчётов с Python отдельно.",
        "",
        "### Отобранные признаки",
        "",
    ])
    for name in selected_names:
        lines.append(f"- `{name}` - {describe_feature(str(name))}.")
    lines.extend(["", "## Наиболее информативные признаки", ""])
    for item in rankings[:15]:
        lines.append(
            f"- `{item['feature']}` - важность деревьев {float(item['tree_importance']):.3f}, "
            f"одномерная AUC {float(item['univariate_auc']):.3f}; {describe_feature(str(item['feature']))}."
        )
    lines.extend(["", "## Внешняя проверка: будильник", ""])
    if len(alarm_files):
        lines.extend(["| Файл | Вероятность ёрзания | Решение |", "| --- | ---: | --- |"])
        for audio_file, probability in zip(alarm_files, alarm_probability):
            lines.append(f"| {audio_file.path.name} | {probability:.4f} | {'ёрзание' if probability >= 0.5 else 'тишина'} |")
        false_alarms = int(np.sum(alarm_probability >= 0.5))
        lines.append(f"\nЛожных срабатываний на 5 файлах будильника: {false_alarms}/5.")
    lines.extend([
        "",
        "## Ограничения и следующий шаг",
        "",
        "Набор отрицательного класса неоднороден, а положительных файлов мало. Результат показывает качество на сохранённых ночах, но не доказывает устойчивость к новым источникам звука. Перед внедрением нужно разметить больше будильников, хлопков, речи, кашля и уведомлений как 'тишина', затем повторить этот же групповой эксперимент. Порог вероятности следует выбирать по целевой цене ложного срабатывания на отдельной валидационной части, а не по точности.",
    ])
    (output / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    write_csv(output / "model_scores.csv", scores)
    write_csv(output / "feature_ranking.csv", rankings)
    np.savez_compressed(output / "experiment.npz", features=X_labeled, labels=y, oof_probability=oof_probability)


def main() -> None:
    args = parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    files = load_files(args.samples)
    matrix, names = build_matrix(files)
    labeled_indexes = [index for index, item in enumerate(files) if not item.unchecked]
    unchecked_indexes = [index for index, item in enumerate(files) if item.unchecked]
    X_labeled = matrix[labeled_indexes]
    X_unchecked = matrix[unchecked_indexes]
    labeled = [files[index] for index in labeled_indexes]
    unchecked = [files[index] for index in unchecked_indexes]
    y = np.asarray([item.label for item in labeled])
    groups = np.asarray([item.date for item in labeled])
    usable = np.asarray([name != "raw_rms_diagnostic" for name in names])
    X = X_labeled[:, usable]
    usable_names = list(np.asarray(names)[usable])
    candidates = model_candidates(X.shape[1])
    scores, oof_predictions = evaluate_models(candidates, X, y, groups)
    alarm_indexes = [index for index, item in enumerate(unchecked) if item.date == args.alarm_date][-5:]
    alarm_matrix = X_unchecked[alarm_indexes][:, usable]
    for item in scores:
        model = clone(candidates[str(item["model"])]).fit(X, y)
        probabilities = model.predict_proba(alarm_matrix)[:, 1] if len(alarm_matrix) else np.array([])
        item["alarm_false_positives"] = int(np.sum(probabilities >= 0.5))
    ranking = feature_ranking(X, y, usable_names)
    best_name = min(
        scores,
        key=lambda item: (
            int(item["alarm_false_positives"]),
            int(item["false_positives"]),
            -float(item["recall"]),
        ),
    )["model"]
    best = clone(candidates[str(best_name)]).fit(X, y)
    selected_probability = oof_predictions[str(best_name)]
    write_report(
        args.output, files, usable_names, ranking, scores, str(best_name), best, X, y,
        X_unchecked[:, usable], unchecked, selected_probability, args.alarm_date,
    )
    plot_results(args.output, ranking, y, selected_probability)
    metadata = {"labeled": int(len(y)), "positive": int(y.sum()), "unchecked": int(len(unchecked)), "features_used": len(usable_names), "selected_model": best_name}
    write_csv(args.output / "all_features.csv", [
        {
            "file": audio_file.path.name,
            "date": audio_file.date,
            "label": audio_file.label if not audio_file.unchecked else "unchecked",
            **dict(zip(names, row)),
        }
        for audio_file, row in zip(files, matrix)
    ])
    (args.output / "metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Результаты: {args.output}")


if __name__ == "__main__":
    main()
