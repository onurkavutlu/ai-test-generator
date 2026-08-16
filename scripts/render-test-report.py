#!/usr/bin/env python3

"""Maven test koşumundan ölçülmüş Surefire/JaCoCo Markdown raporu üretir."""

from __future__ import annotations

import argparse
import csv
import xml.etree.ElementTree as ET
from pathlib import Path


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--command", required=True)
    parser.add_argument("--java-version", required=True)
    parser.add_argument("--start-iso", required=True)
    parser.add_argument("--end-iso", required=True)
    parser.add_argument("--start-ns", required=True, type=int)
    parser.add_argument("--end-ns", required=True, type=int)
    parser.add_argument("--exit-code", required=True, type=int)
    parser.add_argument("--log-file", required=True)
    parser.add_argument("--report-file", required=True)
    return parser.parse_args()


def current_xml_reports(root: Path, start_ns: int) -> list[Path]:
    reports = []
    for pattern in ("target/surefire-reports/TEST-*.xml", "target/failsafe-reports/TEST-*.xml"):
        for path in root.glob(pattern):
            if path.stat().st_mtime_ns >= start_ns:
                reports.append(path)
    return sorted(reports)


def test_totals(paths: list[Path]) -> dict[str, float]:
    totals: dict[str, float] = {
        "tests": 0,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "time": 0.0,
    }
    for path in paths:
        suite = ET.parse(path).getroot()
        for key in ("tests", "failures", "errors", "skipped"):
            totals[key] += int(suite.attrib.get(key, 0))
        totals["time"] += float(suite.attrib.get("time", 0.0))
    return totals


def skipped_reasons(paths: list[Path]) -> list[str]:
    reasons: set[str] = set()
    for path in paths:
        suite = ET.parse(path).getroot()
        for skipped in suite.findall(".//skipped"):
            raw = skipped.attrib.get("message") or skipped.text or ""
            first_line = raw.strip().splitlines()[0] if raw.strip() else "Neden XML raporunda belirtilmedi."
            reasons.add(first_line)
    return sorted(reasons)


def coverage(root: Path, start_ns: int) -> tuple[str, str] | None:
    csv_path = root / "target/site/jacoco/jacoco.csv"
    if not csv_path.exists() or csv_path.stat().st_mtime_ns < start_ns:
        return None

    line_missed = line_covered = branch_missed = branch_covered = 0
    with csv_path.open(newline="", encoding="utf-8") as stream:
        for row in csv.DictReader(stream):
            line_missed += int(row["LINE_MISSED"])
            line_covered += int(row["LINE_COVERED"])
            branch_missed += int(row["BRANCH_MISSED"])
            branch_covered += int(row["BRANCH_COVERED"])

    def ratio(covered: int, missed: int) -> str:
        total = covered + missed
        return "N/A" if total == 0 else f"{covered / total * 100:.2f}%"

    return ratio(line_covered, line_missed), ratio(branch_covered, branch_missed)


def main() -> None:
    args = arguments()
    root = Path(args.project_root).resolve()
    report_file = Path(args.report_file).resolve()
    log_file = Path(args.log_file).resolve()
    xml_reports = current_xml_reports(root, args.start_ns)
    totals = test_totals(xml_reports)
    measured_skip_reasons = skipped_reasons(xml_reports)
    measured_coverage = coverage(root, args.start_ns)
    duration = (args.end_ns - args.start_ns) / 1_000_000_000
    status = "BAŞARILI" if args.exit_code == 0 else "BAŞARISIZ"

    lines = [
        f"# Test Koşum Raporu — {args.label}",
        "",
        f"- Sonuç: **{status}**",
        f"- Başlangıç: `{args.start_iso}`",
        f"- Bitiş: `{args.end_iso}`",
        f"- Duvar süresi: `{duration:.2f} sn`",
        f"- Java: `{args.java_version}`",
        f"- Maven çıkış kodu: `{args.exit_code}`",
        f"- Komut: `{args.command.strip()}`",
        "",
        "## Ölçülen test sonuçları",
        "",
        f"- Test: `{int(totals['tests'])}`",
        f"- Başarısız assertion: `{int(totals['failures'])}`",
        f"- Hata: `{int(totals['errors'])}`",
        f"- Atlanan: `{int(totals['skipped'])}`",
        f"- Surefire/Failsafe toplam test süresi: `{totals['time']:.3f} sn`",
        f"- Bu koşumda güncellenen XML suite sayısı: `{len(xml_reports)}`",
    ]
    if measured_skip_reasons:
        lines.extend(["", "### XML’den alınan atlama nedenleri", ""])
        lines.extend(f"- {reason}" for reason in measured_skip_reasons)

    lines.extend(["", "## Kapsam", ""])
    if measured_coverage is None:
        lines.append("Bu koşumda güncellenmiş JaCoCo CSV raporu oluşmadı; kapsam değeri raporlanmadı.")
    else:
        lines.extend([
            f"- Satır kapsamı: `{measured_coverage[0]}`",
            f"- Dal kapsamı: `{measured_coverage[1]}`",
        ])

    lines.extend([
        "",
        "## Kanıt dosyaları",
        "",
        f"- Maven günlüğü: `{log_file.relative_to(root)}`",
    ])
    if xml_reports:
        lines.append("- Güncellenen test suite raporları:")
        lines.extend(f"  - `{path.relative_to(root)}`" for path in xml_reports)
    else:
        lines.append("- Bu koşumda Surefire/Failsafe XML raporu güncellenmedi.")

    report_file.parent.mkdir(parents=True, exist_ok=True)
    report_file.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
