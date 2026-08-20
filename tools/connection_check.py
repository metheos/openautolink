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
    connection_check.py                 # check every car and companion log
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
PHONE_LOG_GLOB = "/Docker/oal-logs/canonical/*/oal_companion_*.log"

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
M_CODEC_MARKERS = ("Codec selected", "Phone negotiated codec type:")
M_NO_COMPANION = "No companion on any local subnet"
M_COMPANION_FOUND = "reports AA proxy on port"
M_COMPANION_SCAN_FOUND = "Companion found at"
M_READVERTISE = "re-advertising so the phone retries"
M_DISCOVERY_FOUND = "preferred sweep complete: 1 phone"
M_IGNITION_OFF = "IGNITION_STATE → 2 (OFF)"
# A native session start without the full setup that normally accompanies it.
# Three separate in-vehicle failures came from this asymmetry: no decoder (black
# video), no surface (black video), and no session reference (touch silently
# dropped while video streamed perfectly).
M_NATIVE_START = "Starting native aasdk session"
M_FULL_SETUP = "aasdk JNI session started"
# Legacy intent marker retained for display only. It was conditional, so its
# absence cannot prove dependencies were skipped. New builds emit the explicit
# per-start outcome below, which is the only marker used for enforcement.
M_READOPT = "Re-adopting the session after a transport restart"
M_SESSION_DEPS_READY = "Native session dependencies ready:"
# Audio: the phone announcing playback vs frames actually reaching the player.
# "Audio start" with no aflow means every upstream signal is healthy — channel
# open, codec agreed, phone sending — and nothing arrives. Silent playback with
# stats showing no audio at all.
M_AUDIO_START = "Audio start (type="
# NOT "I/aflow:" — that tag does not exist in any log, in any build. The rule was
# written against an invented marker, so it never matched and every session that
# started audio was reported as silent. Validated instead against the two known
# cases: the 00:17 session where audio was genuinely broken has 7 audio starts and
# 0 AudioTrack lines; the 01:21 session where the user confirmed audio works has 1
# and 1.
M_AUDIO_FLOW = "AudioTrack started"
# The car opened a socket to the companion and the phone never answered. Means
# Android Auto was never told which loopback port to attach to — the Bluetooth
# handshake carries that, and a reconnect does not run one.
M_HANDSHAKE_TIMEOUT = "handshake timeout (15s, no SSL/version response)"
# Dialling the home router: the companion is never at the gateway in WPP mode.
M_GATEWAY_DIAL = "(gateway)"
# Handshakes completing over and over with no session: our side advertised the
# right endpoint every time, and never opened its own half of the connection.
M_HANDSHAKE_OK_LINE = "Handshake complete"
# A session torn down within a second of being created is us fighting ourselves:
# a re-advertise triggers a handshake, the handshake dials, the dial replaces the
# session the handshake was for. Distinct from a session that ran and then failed.
M_NATIVE_START_LINE = "Starting native aasdk session"
M_SELF_TEARDOWN = "stop() closing transport WITHOUT ByeBye reason=reconnect"
# Ignition ON with no advertiser activity afterwards: the SDP record is never
# published, so the phone is never told which network to join and simply stays
# where it is. Produces a totally silent failure — no error, no retry, nothing.
M_IGNITION_ON = "IGNITION_STATE → 4 (ON)"
# The app stopped responding: Android dumped every thread to tombstoned. Reads as
# a crash to the user (the app restarts), but it is a blocked main thread.
M_ANR = "Wrote stack traces to tombstoned"
# Native process-fatal signals. SIGQUIT is deliberately excluded: Android uses
# signal 3 for ANR thread dumps and the process survives. The app crash-handler
# marker is authoritative; generic libc lines count only when the same line names
# the OAL process, because paired logcat can contain unrelated processes.
M_NATIVE_CRASH_HANDLER = re.compile(
    r"NATIVE CRASH: signal=(?:6|11) \((SIGABRT|SIGSEGV)\)"
)
M_FATAL_SIGNAL = re.compile(r"Fatal signal (?:6|11) \((SIGABRT|SIGSEGV)\)")
M_ADVERTISER_ANY = "AaWirelessBt"
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
    last_event_at: float = 0.0
    last_native_transport: str | None = None
    surface_attached: bool = False
    readvertised: bool = False
    retries: int = 0
    native_starts: int = 0
    full_setups: int = 0
    setup_nearby: bool = False
    native_start_times: list = field(default_factory=list)
    uncovered_starts: list = field(default_factory=list)
    audio_starts: int = 0
    audio_flows: int = 0
    ssl_timeouts: int = 0
    gateway_dials: int = 0
    handshakes: int = 0
    self_teardowns: int = 0
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
    # Explicit dependency-outcome timestamps for the whole log. The outcome is
    # logged within milliseconds of its native start and can straddle a bucket.
    setup_times: list[float] = []

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
            current.last_event_at = t

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
            elif any(marker in line for marker in M_CODEC_MARKERS) and current.codec_at is None:
                current.codec_at = t
            elif M_SURFACE_ATTACH in line:
                current.surface_attached = True
            elif M_NATIVE_START in line:
                current.native_starts += 1
                current.native_start_times.append(t)
                current.last_native_transport = "usb" if "(USB)" in line else "tcp"
            elif M_SESSION_DEPS_READY in line:
                current.full_setups += 1
                setup_times.append(t)
            elif M_FULL_SETUP in line or M_READOPT in line:
                # Legacy intent/proxy markers are not proof that every dependency
                # was ready for this exact native start. Keep the count for display
                # but enforce only the explicit outcome marker above.
                current.full_setups += 1
            elif M_HANDSHAKE_OK_LINE in line:
                current.handshakes += 1
                if current.handshake_ok_at is None:
                    current.handshake_ok_at = t
            elif M_HANDSHAKE_TIMEOUT in line and current.last_native_transport != "usb":
                current.ssl_timeouts += 1
            elif M_GATEWAY_DIAL in line:
                current.gateway_dials += 1
            elif M_SELF_TEARDOWN in line:
                current.self_teardowns += 1
            elif M_AUDIO_START in line:
                current.audio_starts += 1
            elif M_AUDIO_FLOW in line:
                current.audio_flows += 1
            elif M_READVERTISE in line:
                current.readvertised = True
            elif M_DISCOVERY_FOUND in line and not current.connected:
                current.discovery_found_after = True

    # A native start is "covered" if its explicit dependency outcome is within 2s.
    # Judge EVERY native start on its own. An attempt can span minutes — the one
    # at 22:33:54 ran until 22:37 — so "does this attempt contain a setup?" lets a
    # later recovery's setup vouch for an earlier start that never had one, which
    # is exactly backwards. The first native start after a stop() is the one at
    # risk, and it is the one that must have its own setup.
    if not setup_times:
        for attempt in attempts:
            attempt.uncovered_starts = []
            attempt.setup_nearby = True
    else:
        start_refs = [
            (attempt_index, start_index, started_at)
            for attempt_index, attempt in enumerate(attempts)
            for start_index, started_at in enumerate(attempt.native_start_times)
        ]
        unmatched = set(range(len(start_refs)))
        covered: set[int] = set()
        for outcome_at in sorted(setup_times):
            candidates = [
                ref_index for ref_index in unmatched
                if abs(start_refs[ref_index][2] - outcome_at) <= 2.0
            ]
            if not candidates:
                continue
            winner = min(candidates, key=lambda index: abs(start_refs[index][2] - outcome_at))
            unmatched.remove(winner)
            covered.add(winner)
        for attempt_index, attempt in enumerate(attempts):
            attempt.uncovered_starts = [
                started_at
                for ref_index, (owner_index, _, started_at) in enumerate(start_refs)
                if owner_index == attempt_index and ref_index not in covered
            ]
            attempt.setup_nearby = not attempt.uncovered_starts
    return attempts


@dataclass
class Finding:
    severity: str      # "FAIL" | "WARN" | "INFO"
    rule: str
    detail: str


SUMMARY_FIELD_RE = re.compile(r"\b([A-Za-z][A-Za-z0-9]*)=([^\s]+)")
SUMMARY_STAGE_RE = re.compile(r"(?:^|>)([A-Z_]+)@(\d+)")
M_WAKE_SUMMARY = "WAKE SUMMARY "
M_PHONE_WPP_SUMMARY = "PHONE WPP SUMMARY "
M_FIRST_FRAME_RENDERED = "first frame rendered"


def _stable_summaries(text: str, marker: str) -> list[dict[str, str]]:
    """Parse the key=value contract that precedes each bounded summary timeline."""
    summaries = []
    for line in text.splitlines():
        marker_at = line.find(marker)
        if marker_at < 0:
            continue
        fixed, timeline_marker, timeline = line[marker_at:].partition(" timeline=")
        fields = dict(SUMMARY_FIELD_RE.findall(fixed))
        if timeline_marker:
            fields["timeline"] = timeline
        summaries.append(fields)
    return summaries


def _stage_times(summary: dict[str, str]) -> dict[str, list[int]]:
    stages: dict[str, list[int]] = {}
    for stage, elapsed in SUMMARY_STAGE_RE.findall(summary.get("timeline", "")):
        stages.setdefault(stage, []).append(int(elapsed))
    return stages


def _elapsed_field(summary: dict[str, str], name: str) -> int | None:
    value = summary.get(name, "-")
    return int(value) if value.isdigit() else None


def positive_cross_side_success(text: str) -> bool:
    """True only for one ordered bridge -> session -> rendered-flow sequence.

    Two positive vflow windows *after* the rendered-frame marker are the minimum
    evidence that video continued rather than producing one seed frame and dying.
    Requiring ordering also prevents unrelated attempts in an appended log from
    donating one success marker each. Old logs are intentionally not failures;
    they simply cannot prove this result.
    """
    bridge_seen = False
    native_seen = False
    rendered_seen = False
    positive_vflow_windows = 0
    for line in text.splitlines():
        phone_summaries = _stable_summaries(line, M_PHONE_WPP_SUMMARY)
        if "Bridge established" in line or any(
            summary.get("outcome") == "connected"
            or "BRIDGE_ESTABLISHED" in _stage_times(summary)
            for summary in phone_summaries
        ):
            bridge_seen = True
            native_seen = False
            rendered_seen = False
            positive_vflow_windows = 0
            continue
        if bridge_seen and M_SESSION_UP in line:
            native_seen = True
            rendered_seen = False
            positive_vflow_windows = 0
            continue
        if native_seen and M_FIRST_FRAME_RENDERED in line.lower():
            rendered_seen = True
            positive_vflow_windows = 0
            continue
        if rendered_seen and "vflow:" in line:
            match = re.search(r"\bframes=(\d+)", line)
            if match and int(match.group(1)) > 0:
                positive_vflow_windows += 1
                if positive_vflow_windows >= 2:
                    return True
    return False


def check_summary_text(text: str) -> list[Finding]:
    """Check stable cross-side summary markers without penalising older builds."""
    out: list[Finding] = []

    for summary in _stable_summaries(text, M_WAKE_SUMMARY):
        attempt = summary.get("attempt", "?")
        if summary.get("gmSystemState") == "not_observed":
            out.append(Finding(
                "INFO", "gm-signal-unavailable",
                f"wake attempt {attempt}: GM system-state signal was not_observed; "
                "ignition and standard Android signals remain valid fallbacks",
            ))

        ap_ready = _elapsed_field(summary, "ap")
        ignition = _elapsed_field(summary, "ignition")
        if ap_ready is not None and ignition is not None and ap_ready < ignition:
            out.append(Finding(
                "INFO", "ap-ready-before-ignition",
                f"wake attempt {attempt}: AP ready {ignition - ap_ready}ms before ignition",
            ))

        stages = _stage_times(summary)
        sdp_times = stages.get("SDP_PUBLISHED", [])
        session_ready = _elapsed_field(summary, "session")
        if session_ready is None:
            session_times = stages.get("SESSION_READY", [])
            session_ready = min(session_times) if session_times else None
        if sdp_times and (session_ready is None or min(sdp_times) < session_ready):
            readiness = (
                "never became ready"
                if session_ready is None
                else f"was ready at {session_ready}ms"
            )
            out.append(Finding(
                "FAIL", "sdp-before-session-ready",
                f"wake attempt {attempt}: SDP published at {min(sdp_times)}ms before "
                f"the current native session {readiness}",
            ))

    for summary in _stable_summaries(text, M_PHONE_WPP_SUMMARY):
        attempt = summary.get("attempt", "?")
        stages = _stage_times(summary)
        outcome = summary.get("outcome", "unknown")
        missing = summary.get("missing", "unknown")
        has_bt = "TARGET_BT_CONNECTED" in stages
        # A connected outcome and missing=none are stable proof even if the bounded
        # timeline was truncated before its later socket stages.
        has_car_socket = (
            "CAR_SOCKET" in stages or outcome == "connected" or missing == "none"
        )
        has_aa_socket = (
            "AA_SOCKET" in stages or outcome == "connected" or missing == "none"
        )
        if has_bt and not has_car_socket:
            out.append(Finding(
                "FAIL", "phone-bt-no-car-socket",
                f"phone WPP attempt {attempt}: selected-car BT was seen but no car "
                f"socket arrived before terminal outcome={outcome}",
            ))
        if has_aa_socket and not has_car_socket:
            out.append(Finding(
                "INFO", "aa-socket-before-car-socket",
                f"phone WPP attempt {attempt}: AA socket is waiting_for_car, not connected",
            ))

    if positive_cross_side_success(text):
        out.append(Finding(
            "INFO", "cross-side-success",
            "bridge established, native AA session started, first frame rendered, "
            "and video flow continued across multiple windows",
        ))
    return out


def check_summary_markers(path: str) -> list[Finding]:
    try:
        with open(path, errors="ignore") as fh:
            return check_summary_text(fh.read())
    except OSError:
        return []


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

    # A native session that never ran full setup keeps whatever the last stop()
    # left behind: null decoder, no surface, or — the one that took longest to
    # find — a null session reference, which drops every touch while video looks
    # perfect. Save & Reconnect "fixing" it is the tell: that path does run setup.
    # Compare within the attempt AND allow a setup that ran just before it: the
    # full setup is logged a few ms before the native start, so a naive bucket
    # comparison lands them either side of an attempt boundary and reports the
    # opposite of the truth. First version of this rule flagged the two HEALTHY
    # sessions and missed the broken one.
    if a.connected and a.uncovered_starts:
        out.append(Finding(
            "WARN", "session-start-without-setup",
            f"{len(a.uncovered_starts)} of {a.native_starts} native session "
            "start(s) had no dependency-ready outcome — decoder, surface, session "
            "reference or audio collectors may be stale",
        ))

    # A dense burst is already the feedback loop, even when the whole file has
    # fewer than the old lifetime threshold of eight. The 2026-08-13 Liz-phone
    # run destroyed a working bridge three times in nine seconds; the file-level
    # rule missed it because the appended file held only seven total teardowns.
    if a.self_teardowns >= 3:
        out.append(Finding(
            "FAIL", "reconnect-feedback-loop",
            f"{a.self_teardowns} self-inflicted teardowns in one attempt — "
            "a re-dial is replacing the live session it was meant to use",
        ))

    # The peer keeps re-handshaking because our side never completes its half.
    if a.handshakes >= 10 and not a.connected:
        out.append(Finding(
            "FAIL", "handshake-loop",
            f"{a.handshakes} completed handshakes and no session — the endpoint "
            "advertised was right; our side never opened its half",
        ))

    # Socket opened, phone never spoke: nothing told Android Auto where to attach.
    if a.ssl_timeouts > 0:
        out.append(Finding(
            "FAIL", "connected-but-no-handshake",
            f"{a.ssl_timeouts} session(s) timed out waiting for the phone — the "
            "TCP link came up but Android Auto was never pointed at the proxy",
        ))

    if a.gateway_dials >= 3:
        out.append(Finding(
            "WARN", "gateway-dialling",
            f"{a.gateway_dials} attempts to the network gateway — in WPP mode the "
            "companion is never the gateway, this is usually the house router",
        ))

    # A final upload can cut the log between native-start success and codec
    # selection. Give a completed attempt two seconds of observed tail before
    # calling the codec absent; otherwise the checker invents a fault at EOF.
    has_codec_observation_window = (
        a.session_up_at is not None and a.last_event_at - a.session_up_at >= 2.0
    )
    if a.connected and a.codec_at is None and has_codec_observation_window:
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


def check_transport_agnostic(path: str) -> list:
    """Bug classes that have nothing to do with which transport is in use.

    parse_log() anchors every attempt on the WPP dial-back line, so pre-WPP logs
    produce zero attempts and every per-attempt rule is silently skipped — 48
    logs examined by nothing at all. These faults live in the session layer, not
    the transport, so they are checked per-log instead.
    """
    out = []
    try:
        with open(path, errors="ignore") as fh:
            text = fh.read()
    except OSError:
        return out
    # The ANR marker is emitted by the platform, so it only appears in the paired
    # logcat capture — scanning the app's own file log alone would never see it.
    logcat_path = path.replace("/oal_", "/logcat_")
    try:
        with open(logcat_path, errors="ignore") as fh:
            logcat_text = fh.read()
    except OSError:
        logcat_text = ""

    # New builds positively report every native start's dependencies. Enforce only
    # when that marker exists; legacy silence cannot distinguish safe conditional
    # rebinding from a skipped setup.
    starts = text.count(M_NATIVE_START_LINE)
    dependency_outcomes = text.count(M_SESSION_DEPS_READY)
    if starts > 0 and dependency_outcomes > 0 and dependency_outcomes < starts:
        uncovered = starts - dependency_outcomes
        # Only worth reporting when it is a pattern, not a single restart.
        if uncovered >= 5:
            out.append(Finding(
                "WARN", "setup-skipped-sessions",
                f"{uncovered} of {starts} native session starts had no explicit "
                "dependency-ready outcome — decoder, surface, session reference "
                "or audio collectors may be stale",
            ))

    # Main thread stopped responding.
    anrs = logcat_text.count(M_ANR)
    if anrs:
        out.append(Finding(
            "FAIL", "app-not-responding",
            f"{anrs} ANR(s) — the main thread stopped responding long enough for "
            "Android to dump threads; the app restarts and looks like a crash",
        ))

    # Native SIGABRT/SIGSEGV kills the process. OAL's crash handler and libc can
    # both describe the same incident, so report distinct signal names rather
    # than counting duplicate marker lines.
    native_crash_signals = set(M_NATIVE_CRASH_HANDLER.findall(logcat_text))
    for line in logcat_text.splitlines():
        if "penautolink.app" not in line and "com.openautolink.app" not in line:
            continue
        native_crash_signals.update(M_FATAL_SIGNAL.findall(line))
    native_crash_signals = sorted(native_crash_signals)
    if native_crash_signals:
        out.append(Finding(
            "FAIL", "native-crash",
            f"native process crash ({', '.join(native_crash_signals)}) — "
            "the app process was killed and any later connection used a new process",
        ))

    # The phone announced audio and none of it reached the player.
    a_start = text.count(M_AUDIO_START)
    a_flow = text.count(M_AUDIO_FLOW)
    if a_start > 0 and a_flow == 0:
        out.append(Finding(
            "FAIL", "audio-never-reached-player",
            f"{a_start} audio start(s) and no AudioTrack — channel negotiated, "
            "nothing played",
        ))
    return out


def check_silent_advertiser(path: str) -> list:
    """Ignition ON, then no advertiser activity at all.

    Distinct from every other failure here because there is no error to find —
    the guard that refuses to start returns before it logs anything. Measured
    2026-08-10 16:46: ignition ON, 4 minutes of discovery sweeps, and not one
    AaWirelessBt line, because an in-flight flag had been latched since 08:50.
    """
    out = []
    try:
        with open(path, errors="ignore") as fh:
            lines = fh.readlines()
    except OSError:
        return out
    # Only meaningful for builds that HAVE a Bluetooth advertiser. 48 of the 59
    # logs this first flagged were pre-WPP builds with no advertiser at all —
    # a rule that fires on four-fifths noise is a rule nobody reads.
    if not any(M_ADVERTISER_ANY in l for l in lines):
        return out
    for i, line in enumerate(lines):
        if M_IGNITION_ON not in line:
            continue
        after = lines[i + 1:]
        # A log that simply ends after ignition ON proves nothing.
        if len(after) < 40:
            continue
        if not any(M_ADVERTISER_ANY in l for l in after):
            out.append(Finding(
                "FAIL", "advertiser-never-started",
                f"ignition ON at {line[:12].strip()} and no advertiser activity "
                "afterwards — no SDP record, so the phone is never told which "
                "network to join",
            ))
            break
    return out


def check_log_level(attempts: list) -> list:
    """Findings that only show up across a whole log, not one attempt.

    The reconnect feedback loop spreads thinly — measured 20 self-inflicted
    teardowns across 9 attempts, never more than 4 in any one — so a per-attempt
    threshold misses it entirely. The signal is the total.
    """
    out = []
    total = sum(a.self_teardowns for a in attempts)
    if total >= 8:
        out.append(Finding(
            "FAIL", "reconnect-feedback-loop",
            f"{total} self-inflicted teardowns across {len(attempts)} attempts — "
            "a re-advertise triggers a handshake whose dial destroys the session "
            "that handshake was for",
        ))
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="*", help="specific log files (default: all car logs)")
    ap.add_argument("--since", help="only logs whose name contains this (e.g. 08-08)")
    ap.add_argument("--verbose", "-v", action="store_true", help="show passing attempts too")
    args = ap.parse_args()

    paths = args.files or sorted(glob.glob(CAR_LOG_GLOB) + glob.glob(PHONE_LOG_GLOB))
    if args.since:
        paths = [p for p in paths if args.since in os.path.basename(p)]
    if not paths:
        print("no logs matched", file=sys.stderr)
        return 2

    total = connected = 0
    fails: dict[str, int] = {}
    warns: dict[str, int] = {}
    infos: dict[str, int] = {}
    reported: list[str] = []

    for path in paths:
        is_car_log = not os.path.basename(path).startswith("oal_companion_")
        log_attempts = parse_log(path) if is_car_log else []
        old_findings = (
            check_transport_agnostic(path)
            + check_silent_advertiser(path)
            + check_log_level(log_attempts)
        ) if is_car_log else []
        for f in old_findings + check_summary_markers(path):
            bucket = (
                fails if f.severity == "FAIL"
                else warns if f.severity == "WARN"
                else infos
            )
            bucket[f.rule] = bucket.get(f.rule, 0) + 1
            reported.append(f"{os.path.basename(path)}  (whole log)")
            reported.append(f"    {f.severity}  {f.rule}: {f.detail}")
        for a in log_attempts:
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
    if infos:
        print("info:", ", ".join(f"{k}={v}" for k, v in sorted(infos.items())))

    # Non-zero exit when anything failed, so this can gate a change.
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
