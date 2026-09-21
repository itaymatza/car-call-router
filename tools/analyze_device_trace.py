#!/usr/bin/env python3
"""Summarize privacy-safe ROUTING_TRACE records from Car Call Router."""

from __future__ import annotations

import argparse
import csv
import json
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable, TextIO


MARKER = "ROUTING_TRACE "
SUPPORTED_SCHEMA = "1"


@dataclass(frozen=True)
class TraceEvent:
    line: int
    schema: str
    session: str
    seq: int
    elapsed_ms: int
    event: str
    fields: dict[str, str]


@dataclass
class SessionSummary:
    session: str
    status: str
    mode: str
    trigger: str
    requests: int
    selector_recoveries: int
    endpoint_callbacks: int
    self_callbacks: int
    startup_replays: int
    external_callbacks: int
    route_changes: int
    route_oscillations: int
    endpoint_confirmed: bool
    endpoint_latency_ms: int | None
    hfp_audio_confirmed: bool
    hfp_latency_ms: int | None
    final_phase: str
    final_reason: str
    termination: str
    best_confirmation: str
    final_route: str
    events: int
    anomalies: list[str]


def parse_lines(lines: Iterable[str]) -> tuple[list[TraceEvent], list[str]]:
    events: list[TraceEvent] = []
    warnings: list[str] = []
    for line_number, raw in enumerate(lines, start=1):
        if MARKER not in raw:
            continue
        body = raw.split(MARKER, 1)[1].strip()
        values: dict[str, str] = {}
        malformed = False
        for token in body.split():
            if "=" not in token:
                malformed = True
                continue
            key, value = token.split("=", 1)
            if not key or key in values:
                malformed = True
                continue
            values[key] = value
        required = ("schema", "session", "seq", "elapsed_ms", "event")
        missing = [key for key in required if key not in values]
        if malformed or missing:
            detail = f"line {line_number}: malformed trace record"
            if missing:
                detail += f" (missing {','.join(missing)})"
            warnings.append(detail)
            continue
        try:
            seq = int(values.pop("seq"))
            elapsed_ms = int(values.pop("elapsed_ms"))
        except ValueError:
            warnings.append(f"line {line_number}: seq and elapsed_ms must be integers")
            continue
        schema = values.pop("schema")
        session = values.pop("session")
        event = values.pop("event")
        events.append(TraceEvent(line_number, schema, session, seq, elapsed_ms, event, values))
    return events, warnings


def _first(events: list[TraceEvent], name: str) -> TraceEvent | None:
    return next((event for event in events if event.event == name), None)


def summarize(events: Iterable[TraceEvent], excluded: set[str] | None = None) -> list[SessionSummary]:
    grouped: dict[str, list[TraceEvent]] = {}
    excluded = excluded or set()
    for event in events:
        if event.session not in excluded:
            grouped.setdefault(event.session, []).append(event)

    summaries: list[SessionSummary] = []
    for session, session_events in grouped.items():
        anomalies: list[str] = []
        expected = 1
        previous_elapsed = -1
        for event in session_events:
            if event.schema != SUPPORTED_SCHEMA:
                anomalies.append(f"unsupported schema {event.schema} at line {event.line}")
            if event.seq != expected:
                anomalies.append(f"expected seq {expected}, found {event.seq} at line {event.line}")
                expected = event.seq
            expected += 1
            if event.elapsed_ms < previous_elapsed:
                anomalies.append(f"elapsed_ms decreased at seq {event.seq}")
            if event.elapsed_ms < 0:
                anomalies.append(f"negative elapsed_ms at seq {event.seq}")
            previous_elapsed = event.elapsed_ms

        starts = [event for event in session_events if event.event == "SESSION_STARTED"]
        finishes = [event for event in session_events if event.event == "SESSION_FINISHED"]
        if len(starts) != 1:
            anomalies.append(f"expected one SESSION_STARTED, found {len(starts)}")
        if len(finishes) > 1:
            anomalies.append(f"expected at most one SESSION_FINISHED, found {len(finishes)}")

        start = starts[0] if starts else None
        finish = finishes[-1] if finishes else None
        endpoint = _first(session_events, "TELECOM_ENDPOINT_CONFIRMED")
        hfp = _first(session_events, "TARGET_HFP_AUDIO_CONFIRMED")
        request_callbacks = [event for event in session_events if event.event == "ENDPOINT_REQUEST_OBSERVED"]
        classifications = [
            event.fields.get("classification", event.fields.get("origin", "unknown").upper())
            for event in request_callbacks
        ]
        route_events = [event for event in session_events if event.event == "ENDPOINT_CHANGED"]
        routes = [event.fields.get("route", "UNKNOWN") for event in route_events]
        route_oscillations = sum(
            routes[index] == routes[index - 2] and routes[index] != routes[index - 1]
            for index in range(2, len(routes))
        )
        if anomalies:
            status = "INVALID"
        elif finish is None:
            status = "OPEN"
        elif hfp is not None and route_oscillations > 0:
            status = "UNSTABLE"
        elif hfp is not None:
            status = "PASS"
        elif endpoint is not None:
            status = "INCOMPLETE"
        else:
            status = "FAIL"

        derived_confirmation = (
            "TARGET_HFP_AUDIO" if hfp else "TELECOM_ENDPOINT" if endpoint else "NONE"
        )
        summaries.append(SessionSummary(
            session=session,
            status=status,
            mode=start.fields.get("mode", "unknown") if start else "unknown",
            trigger=start.fields.get("trigger", "unknown") if start else "unknown",
            requests=sum(event.event == "REQUEST_SUBMITTED" for event in session_events),
            selector_recoveries=sum(event.event == "SELECTOR_RECOVERY_SUBMITTED" for event in session_events),
            endpoint_callbacks=len(request_callbacks),
            self_callbacks=classifications.count("SELF"),
            startup_replays=classifications.count("STARTUP_REPLAY"),
            external_callbacks=classifications.count("EXTERNAL"),
            route_changes=len(route_events),
            route_oscillations=route_oscillations,
            endpoint_confirmed=endpoint is not None,
            endpoint_latency_ms=endpoint.elapsed_ms if endpoint else None,
            hfp_audio_confirmed=hfp is not None,
            hfp_latency_ms=hfp.elapsed_ms if hfp else None,
            final_phase=finish.fields.get("phase", "unknown") if finish else "unknown",
            final_reason=finish.fields.get("reason", "unknown") if finish else "unknown",
            termination=finish.fields.get("termination", "open") if finish else "open",
            best_confirmation=finish.fields.get("best_confirmation", derived_confirmation) if finish else derived_confirmation,
            final_route=finish.fields.get("final_route", routes[-1] if routes else "unknown") if finish else (routes[-1] if routes else "unknown"),
            events=len(session_events),
            anomalies=anomalies,
        ))
    return summaries


def render_text(summaries: list[SessionSummary], warnings: list[str], out: TextIO) -> None:
    if not summaries:
        print("No matching routing sessions found.", file=out)
    for item in summaries:
        print(f"Session {item.session}: {item.status}", file=out)
        print(f"  mode/trigger: {item.mode}/{item.trigger}", file=out)
        print(f"  requests: {item.requests}", file=out)
        print(f"  selector recoveries: {item.selector_recoveries}", file=out)
        print(
            f"  endpoint callbacks: {item.endpoint_callbacks} "
            f"(self={item.self_callbacks}, startup_replay={item.startup_replays}, external={item.external_callbacks})",
            file=out,
        )
        print(f"  route changes/oscillations: {item.route_changes}/{item.route_oscillations}", file=out)
        print(f"  Telecom endpoint: {'confirmed' if item.endpoint_confirmed else 'not confirmed'}"
              + (f" at {item.endpoint_latency_ms} ms" if item.endpoint_latency_ms is not None else ""), file=out)
        print(f"  target HFP audio: {'confirmed' if item.hfp_audio_confirmed else 'not confirmed'}"
              + (f" at {item.hfp_latency_ms} ms" if item.hfp_latency_ms is not None else ""), file=out)
        print(
            f"  finish: {item.final_phase}/{item.final_reason}; {item.termination}; "
            f"best={item.best_confirmation}; final_route={item.final_route}",
            file=out,
        )
        for anomaly in item.anomalies:
            print(f"  anomaly: {anomaly}", file=out)
    for warning in warnings:
        print(f"Warning: {warning}", file=out)


def render_csv(summaries: list[SessionSummary], out: TextIO) -> None:
    fieldnames = list(asdict(SessionSummary("", "", "", "", 0, 0, 0, 0, 0, 0, 0, 0,
                                            False, None, False, None, "", "", "", "", "",
                                            0, [])).keys())
    writer = csv.DictWriter(out, fieldnames=fieldnames)
    writer.writeheader()
    for item in summaries:
        row = asdict(item)
        row["anomalies"] = "; ".join(item.anomalies)
        writer.writerow(row)


def read_exclusions(path: Path | None) -> set[str]:
    if path is None:
        return set()
    return {line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip()}


def write_filtered_trace(path: Path, events: Iterable[TraceEvent], excluded: set[str]) -> None:
    with path.open("w", encoding="utf-8") as output:
        for event in events:
            if event.session in excluded:
                continue
            fields = " ".join(f"{key}={value}" for key, value in event.fields.items())
            suffix = f" {fields}" if fields else ""
            output.write(
                f"ROUTING_TRACE schema={event.schema} session={event.session} seq={event.seq} "
                f"elapsed_ms={event.elapsed_ms} event={event.event}{suffix}\n"
            )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="exported app log or ROUTING_TRACE-only file")
    parser.add_argument("--format", choices=("text", "json", "csv"), default="text")
    parser.add_argument("--exclude-session-file", type=Path,
                        help="ignore session IDs listed one per line")
    parser.add_argument("--session-ids-only", action="store_true",
                        help="print parsed session IDs and no report")
    parser.add_argument("--filtered-trace-output", type=Path,
                        help="write canonical trace records after applying session exclusions")
    parser.add_argument("--require-pass", action="store_true",
                        help="exit nonzero unless at least one session exists and all sessions pass")
    args = parser.parse_args(argv)

    try:
        with args.input.open(encoding="utf-8", errors="replace") as source:
            events, warnings = parse_lines(source)
        excluded = read_exclusions(args.exclude_session_file)
    except OSError as error:
        parser.error(str(error))

    if args.session_ids_only:
        for session in dict.fromkeys(event.session for event in events):
            print(session)
        return 0

    summaries = summarize(events, excluded)
    if args.filtered_trace_output is not None:
        try:
            write_filtered_trace(args.filtered_trace_output, events, excluded)
        except OSError as error:
            parser.error(str(error))
    if args.format == "json":
        json.dump({"sessions": [asdict(item) for item in summaries], "warnings": warnings},
                  sys.stdout, indent=2, sort_keys=True)
        print()
    elif args.format == "csv":
        render_csv(summaries, sys.stdout)
    else:
        render_text(summaries, warnings, sys.stdout)

    if args.require_pass and (not summaries or warnings or any(item.status != "PASS" for item in summaries)):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
