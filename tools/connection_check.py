#!/usr/bin/env python3
"""Extract every wireless connection attempt from OAL car logs and check invariants.

Why this exists
---------------
Diagnosing the AA 17.4 wireless work has meant reading 300+ scattered lines per
log to reconstruct one attempt, and confirming a fix has meant driving the car.
Both are slow, and neither answers the question that actually matters after a
change: "did this break a case that used to work?"

This turns every historical log into a regression test. Each connection attempt
becomes a timeline, and each timeline is checked against invariants derived from
runs that genuinely worked. Running it over the whole log archive after a change
shows immediately whether a previously-good run would now fail.

Usage
-----
    connection_check.py                 # check every car log
    connection_check.py --since 08-08   # only logs from a date
    connection_check.py --verbose       # show every attempt, not just failures
    connection_check.py <file>...       # specific logs
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import sys
from dataclasses import dataclass, field

CAR_LOG_GLOB = "/Docker/oal-logs/canonical/Blazer-Car/oal_*.log"

# Dial-backs closer together than this belong to the same attempt.
#
# Measured across 445 gaps: 93% are 1-2s and the next cluster starts at 8s, so
# anything under ~6s is the phone re-dialling inside one failing session rather
# than a new attempt. Grouping matters — without it a single bad session reports
# as hundreds of identical findings and hides everything else.
RETRY_WINDOW_S = 6.0

TS_RE = re.compile(r"^(\d{2}:\d{2}:\d{2}\.\d{3})")


def ts_seconds(line: str) -> float | None:
    """Seconds-since-midnight for a log line, or None if it has no timestamp."""
    m = TS_RE.match(line)
    if not m:
        return None
    h, mnt, rest = m.group(1).split(":")
    return int(h) * 3600 + int(mnt) * 60 + float(rest)


# Markers that define the shape of an attempt. Kept as substrings rather than
# regexes because the log text is stable and substrings survive small wording
# changes better than anchored patterns.
M_ADVERTISE = "Listening on Android Auto Wireless UUID"
M_DIALBACK = "Phone dialled back on the AA Wireless UUID"
M_LOOPBACK = "Advertising the companion's loopback proxy"
M_CARDIRECT = "No companion proxy — advertising this head unit"
M_HANDSHAKE_OK = "Handshake complete"
M_SESSION_UP = "AA session started (native)"
M_SURFACE_ATTACH = "Surface attached"
M_CODEC = "Codec selected"
M_NO_COMPANION = "No companion on any local subnet"
M_COMPANION_FOUND = "reports AA proxy on port"
M_COMPANION_SCAN_FOUND = "Companion found at"
M_READVERTISE = "re-advertising so the phone retries"
M_DISCOVERY_FOUND = "preferred sweep complete: 1 phone"
M_IGNITION_OFF = "IGNITION_STATE → 2 (OFF)"
M_BT_WAIT = "Bluetooth is off — waiting"
M_BT_BACK = "Bluetooth is back"


@dataclass
class Attempt:
    """One phone dial-back and everything that followed it."""

    log: str
    dialback_at: float
    dialback_line: str
    last_dialback_at: float = 0.0
    advertise_at: float | None = None
    endpoint: str | None = None          # "loopback" | "car-direct"
    endpoint_at: float | None = None
    companion_addr: str | None = None
    handshake_ok_at: float | None = None
    session_up_at: float | None = None
    codec_at: float | None = None
    surface_attached: bool = False
    readvertised: bool = False
    retries: int = 0
    discovery_found_after: bool = False
    notes: list[str] = field(default_factory=list)

    @property
    def connected(self) -> bool:
        return self.session_up_at is not None

    @property
    def time_to_endpoint(self) -> float | None:
        if self.endpoint_at is None:
            return None
        return self.endpoint_at - self.dialback_at


def parse_log(path: str) -> list[Attempt]:
    """Split one log into attempts, anchored on each dial-back."""
    attempts: list[Attempt] = []
    current: Attempt | None = None
    name = os.path.basename(path)

    with open(path, errors="ignore") as fh:
        for line in fh:
            line = line.rstrip("\n")
            # The accept-failure spam carries no information and used to be
            # thousands of lines per log.
            if "accept() failed" in line:
                continue
            t = ts_seconds(line)
            if t is None:
                continue

            if M_DIALBACK in line:
                # A dial-back that lands within a couple of seconds of the last
                # one is a retry storm, not a fresh attempt: the phone re-dials
                # every ~1s while a session is failing. Treating each as its own
                # attempt turned one bad session into hundreds of identical
                # findings and buried the real signal.
                if current is not None and t - current.last_dialback_at < RETRY_WINDOW_S:
                    # Compare against the PREVIOUS dial-back, not the first one.
                    # A failing session re-dials continuously — one measured run
                    # was 345 dial-backs over 356 unbroken seconds — so anchoring
                    # on the attempt start splits a single storm into dozens of
                    # "attempts" and reports the same failure dozens of times.
                    current.retries += 1
                    current.last_dialback_at = t
                    continue
                current = Attempt(log=name, dialback_at=t, dialback_line=line,
                                  last_dialback_at=t)
                attempts.append(current)
                continue

            if current is None:
                continue

            if M_LOOPBACK in line and current.endpoint is None:
                current.endpoint = "loopback"
                current.endpoint_at = t
            elif M_CARDIRECT in line and current.endpoint is None:
                current.endpoint = "car-direct"
                current.endpoint_at = t
            elif M_COMPANION_FOUND in line or M_COMPANION_SCAN_FOUND in line:
                m = re.search(r"(\d+\.\d+\.\d+\.\d+)", line)
                if m:
                    current.companion_addr = m.group(1)
            elif M_HANDSHAKE_OK in line and current.handshake_ok_at is None:
                current.handshake_ok_at = t
            elif M_SESSION_UP in line and current.session_up_at is None:
                current.session_up_at = t
            elif M_CODEC in line and current.codec_at is None:
                current.codec_at = t
            elif M_SURFACE_ATTACH in line:
                current.surface_attached = True
            elif M_READVERTISE in line:
                current.readvertised = True
            elif M_DISCOVERY_FOUND in line and not current.connected:
                current.discovery_found_after = True

    return attempts


@dataclass
class Finding:
    severity: str      # "FAIL" | "WARN"
    rule: str
    detail: str


def check(a: Attempt) -> list[Finding]:
    """Invariants, each derived from a failure actually seen in these logs."""
    out: list[Finding] = []

    # The car's access point does not accept inbound connections, so advertising
    # the head unit's own address cannot work. Every Error 21 has this line.
    if a.endpoint == "car-direct":
        out.append(Finding(
            "FAIL", "unreachable-endpoint",
            "advertised the car's own address, which the AP will not accept inbound",
        ))
        # Having then learned where the companion is, we should have retried
        # rather than waiting out the phone's own ~40s timer.
        if a.discovery_found_after and not a.readvertised:
            out.append(Finding(
                "FAIL", "missed-readvertise",
                "discovery found the phone afterwards but no re-advertise followed",
            ))

    # A handshake that completes but never yields a session means the phone was
    # sent somewhere it could not reach.
    if a.handshake_ok_at is not None and not a.connected:
        out.append(Finding(
            "FAIL", "handshake-without-session",
            "handshake completed but no aasdk session started",
        ))

    # The phone re-dialling dozens of times means it kept being told to connect
    # somewhere that did not work — the signature of a teardown without a ByeBye.
    if a.retries >= 10:
        out.append(Finding(
            "FAIL", "retry-storm",
            f"phone re-dialled {a.retries} times without connecting",
        ))

    # Black screen behind a healthy stream: the decoder had nowhere to draw.
    if a.connected and not a.surface_attached:
        out.append(Finding(
            "FAIL", "no-surface",
            "session started but no surface was ever attached (black video)",
        ))

    if a.connected and a.codec_at is None:
        out.append(Finding(
            "WARN", "no-codec",
            "session started but no codec was selected",
        ))

    # Slow is not broken, but it is the difference between "works" and "usable".
    tte = a.time_to_endpoint
    if tte is not None and tte > 15:
        out.append(Finding(
            "WARN", "slow-endpoint",
            f"took {tte:.1f}s from dial-back to choosing an endpoint",
        ))

    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="*", help="specific log files (default: all car logs)")
    ap.add_argument("--since", help="only logs whose name contains this (e.g. 08-08)")
    ap.add_argument("--verbose", "-v", action="store_true", help="show passing attempts too")
    args = ap.parse_args()

    paths = args.files or sorted(glob.glob(CAR_LOG_GLOB))
    if args.since:
        paths = [p for p in paths if args.since in os.path.basename(p)]
    if not paths:
        print("no logs matched", file=sys.stderr)
        return 2

    total = connected = 0
    fails: dict[str, int] = {}
    warns: dict[str, int] = {}
    reported: list[str] = []

    for path in paths:
        for a in parse_log(path):
            total += 1
            if a.connected:
                connected += 1
            findings = check(a)
            for f in findings:
                (fails if f.severity == "FAIL" else warns)[f.rule] = \
                    (fails if f.severity == "FAIL" else warns).get(f.rule, 0) + 1

            if findings or args.verbose:
                stamp = a.dialback_line[:12]
                status = "connected" if a.connected else "NO SESSION"
                retry = f"  retries={a.retries}" if a.retries else ""
                head = (f"{a.log}  {stamp}  endpoint={a.endpoint or 'none'}  "
                        f"companion={a.companion_addr or '-'}  {status}{retry}")
                reported.append(head)
                for f in findings:
                    reported.append(f"    {f.severity}  {f.rule}: {f.detail}")

    print("\n".join(reported) if reported else "no findings")
    print()
    print(f"attempts: {total}   connected: {connected}   "
          f"({100 * connected / total:.0f}%)" if total else "no attempts found")
    if fails:
        print("failures:", ", ".join(f"{k}={v}" for k, v in sorted(fails.items())))
    if warns:
        print("warnings:", ", ".join(f"{k}={v}" for k, v in sorted(warns.items())))

    # Non-zero exit when anything failed, so this can gate a change.
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
