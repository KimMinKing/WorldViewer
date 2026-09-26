#!/usr/bin/env python3
"""Build WorldViewer's deterministic exact-match CC-CEDICT index.

The input is the unmodified ``cedict_ts.u8`` file distributed by MDBG. The
generated index duplicates entries under simplified and traditional
headwords so Android can binary-search it without loading the dictionary into
memory. Dictionary data and this generated representation remain subject to
CC BY-SA 4.0.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import re
import struct
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path


MAGIC = b"WVCCEDIC"
FORMAT_VERSION = 2
ENTRY_PATTERN = re.compile(r"^(\S+) (\S+) \[(.+)] /(.+)/$")
METADATA_PATTERN = re.compile(r"^#! ([a-z]+)=(.*)$")
EXPECTED_LICENSE = "https://creativecommons.org/licenses/by-sa/4.0/"


@dataclass(frozen=True)
class Entry:
    traditional: str
    simplified: str
    pinyin: str
    meanings: tuple[str, ...]


def write_int(output: io.BufferedWriter | io.BytesIO, value: int) -> None:
    output.write(struct.pack(">I", value))


def write_string(output: io.BufferedWriter | io.BytesIO, value: str) -> None:
    encoded = value.encode("utf-8")
    write_int(output, len(encoded))
    output.write(encoded)


def parse_source(path: Path) -> tuple[dict[str, str], list[Entry], dict[str, list[int]], str]:
    source_bytes = path.read_bytes()
    source_sha256 = hashlib.sha256(source_bytes).hexdigest()
    metadata: dict[str, str] = {}
    entries: list[Entry] = []
    by_headword: dict[str, list[int]] = defaultdict(list)
    parsed_entries = 0

    for line_number, line in enumerate(source_bytes.decode("utf-8-sig").splitlines(), 1):
        if line.startswith("#! "):
            match = METADATA_PATTERN.match(line)
            if match:
                metadata[match.group(1)] = match.group(2)
            continue
        if not line or line.startswith("#"):
            continue
        match = ENTRY_PATTERN.match(line)
        if not match:
            raise ValueError(f"Unrecognized CC-CEDICT line {line_number}: {line[:120]!r}")
        traditional, simplified, pinyin, raw_meanings = match.groups()
        meanings = tuple(value.strip() for value in raw_meanings.split("/") if value.strip())
        if not meanings:
            raise ValueError(f"Entry at line {line_number} has no meanings")
        entry = Entry(traditional, simplified, pinyin, meanings)
        entry_id = len(entries)
        entries.append(entry)
        by_headword[simplified].append(entry_id)
        if traditional != simplified:
            by_headword[traditional].append(entry_id)
        parsed_entries += 1

    if metadata.get("license") != EXPECTED_LICENSE:
        raise ValueError(f"Unexpected license metadata: {metadata.get('license')!r}")
    if int(metadata.get("entries", "-1")) != parsed_entries:
        raise ValueError(
            f"Metadata says {metadata.get('entries')} entries, parsed {parsed_entries}"
        )
    return metadata, entries, by_headword, source_sha256


def encode_entry(entry: Entry) -> bytes:
    output = io.BytesIO()
    write_string(output, entry.traditional)
    write_string(output, entry.simplified)
    write_string(output, entry.pinyin)
    write_int(output, len(entry.meanings))
    for meaning in entry.meanings:
        write_string(output, meaning)
    return output.getvalue()


def encode_key(headword: str, entry_ids: list[int]) -> bytes:
    output = io.BytesIO()
    write_string(output, headword)
    write_int(output, len(entry_ids))
    for entry_id in entry_ids:
        write_int(output, entry_id)
    return output.getvalue()


def build(source: Path, output_path: Path, metadata_path: Path) -> None:
    metadata, entries, by_headword, source_sha256 = parse_source(source)
    headwords = sorted(by_headword, key=lambda value: value.encode("utf-8"))
    entry_records = [encode_entry(entry) for entry in entries]
    entry_offsets = [0]
    for record in entry_records:
        entry_offsets.append(entry_offsets[-1] + len(record))
    key_records: list[bytes] = []
    key_offsets = [0]
    for headword in headwords:
        record = encode_key(headword, by_headword[headword])
        key_records.append(record)
        key_offsets.append(key_offsets[-1] + len(record))

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("wb") as output:
        output.write(MAGIC)
        write_int(output, FORMAT_VERSION)
        write_string(output, metadata["date"])
        write_string(output, source_sha256)
        write_int(output, len(entries))
        write_int(output, len(headwords))
        for offset in entry_offsets:
            write_int(output, offset)
        for offset in key_offsets:
            write_int(output, offset)
        for record in entry_records:
            output.write(record)
        for record in key_records:
            output.write(record)

    output_bytes = output_path.read_bytes()
    output_sha256 = hashlib.sha256(output_bytes).hexdigest()
    metadata_path.parent.mkdir(parents=True, exist_ok=True)
    metadata_path.write_text(
        "\n".join(
            [
                "CC-CEDICT generated source record",
                f"provider_version={metadata['date']}",
                f"entry_count={metadata['entries']}",
                f"headword_count={len(headwords)}",
                f"source_sha256={source_sha256}",
                f"generated_sha256={output_sha256}",
                f"generated_bytes={len(output_bytes)}",
                "source_url=https://www.mdbg.net/chinese/dictionary?page=cc-cedict",
                f"license={EXPECTED_LICENSE}",
                "format=WorldViewer exact-match index v2; generated data remains CC BY-SA 4.0",
                "",
            ]
        ),
        encoding="utf-8",
        newline="\n",
    )
    print(
        f"Wrote {output_path} ({len(output_bytes)} bytes, {len(headwords)} headwords, "
        f"sha256 {output_sha256})"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, help="Path to unmodified cedict_ts.u8")
    parser.add_argument("output", type=Path, help="Generated .bin asset")
    parser.add_argument("metadata", type=Path, help="Generated source metadata text")
    args = parser.parse_args()
    build(args.source, args.output, args.metadata)


if __name__ == "__main__":
    main()
