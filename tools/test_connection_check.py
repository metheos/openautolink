#!/usr/bin/env python3
"""Focused synthetic tests for the connection checker's stable summary rules."""

import tempfile
import unittest
from pathlib import Path

from tools.connection_check import (
    check_summary_text,
    check_transport_agnostic,
    positive_cross_side_success,
)


class StableSummaryRulesTest(unittest.TestCase):
    def test_unobserved_gm_signal_is_info_only(self):
        text = (
            "12:00:01.000 I/wake: WAKE SUMMARY attempt=7 trigger=IGNITION_ON "
            "gm=- bt=50 ap=100 apEdge=100 ignition=200 activity=300 session=400 "
            "surface=500 gmSystemState=not_observed gmPowerMode=not_observed "
            "gmPoweroffView=not_observed gmHomeStarted=not_observed "
            "outcome=surface_ready missing=none "
            "timeline=AP_PRESENT@100>IGNITION_ON@200>SESSION_READY@400>"
            "SDP_PUBLISHED@450>SURFACE_READY@500\n"
        )

        findings = check_summary_text(text)
        gm = [finding for finding in findings if finding.rule == "gm-signal-unavailable"]

        self.assertEqual(1, len(gm))
        self.assertEqual("INFO", gm[0].severity)
        self.assertFalse(any(finding.severity == "FAIL" for finding in gm))

    def test_ap_ready_before_ignition_reports_timing(self):
        text = (
            "12:00:01.000 I/wake: WAKE SUMMARY attempt=8 trigger=IGNITION_ON "
            "gm=- bt=50 ap=120 apEdge=- ignition=275 activity=300 session=400 "
            "surface=500 gmSystemState=observed outcome=surface_ready missing=none "
            "timeline=AP_PRESENT@120>IGNITION_ON@275>SESSION_READY@400>"
            "SDP_PUBLISHED@450>SURFACE_READY@500\n"
        )

        findings = check_summary_text(text)
        timing = [finding for finding in findings if finding.rule == "ap-ready-before-ignition"]

        self.assertEqual(1, len(timing))
        self.assertEqual("INFO", timing[0].severity)
        self.assertIn("155ms", timing[0].detail)

    def test_sdp_before_session_ready_is_failure(self):
        text = (
            "12:00:01.000 I/wake: WAKE SUMMARY attempt=9 trigger=IGNITION_ON "
            "gm=- bt=50 ap=120 apEdge=- ignition=275 activity=300 session=400 "
            "surface=500 gmSystemState=observed outcome=surface_ready missing=none "
            "timeline=AP_PRESENT@120>IGNITION_ON@275>SDP_PUBLISHED@350>"
            "SESSION_READY@400>SURFACE_READY@500\n"
        )

        failures = [
            finding for finding in check_summary_text(text)
            if finding.rule == "sdp-before-session-ready"
        ]

        self.assertEqual(1, len(failures))
        self.assertEqual("FAIL", failures[0].severity)

    def test_phone_bt_without_car_socket_is_bounded_to_one_summary_finding(self):
        text = (
            "12:00:01.000 I/Wpp: PHONE WPP EVENT attempt=3 "
            "stage=TARGET_BT_CONNECTED elapsed=10\n"
            "12:00:01.100 I/Wpp: PHONE WPP EVENT attempt=3 "
            "stage=TARGET_BT_CONNECTED elapsed=20\n"
            "12:00:31.000 I/Wpp: PHONE WPP SUMMARY attempt=3 outcome=timeout "
            "missing=CAR_SOCKET timeline=TARGET_BT_CONNECTED@10>AA_SOCKET@50\n"
        )

        findings = check_summary_text(text)
        bounded = [finding for finding in findings if finding.rule == "phone-bt-no-car-socket"]

        self.assertEqual(1, len(bounded))
        self.assertEqual("FAIL", bounded[0].severity)

    def test_aa_socket_before_car_socket_is_waiting_not_connected(self):
        text = (
            "12:00:31.000 I/Wpp: PHONE WPP SUMMARY attempt=4 "
            "outcome=waiting_for_car missing=CAR_SOCKET "
            "timeline=TARGET_BT_CONNECTED@10>AA_SOCKET@50\n"
        )

        findings = check_summary_text(text)
        waiting = [
            finding for finding in findings
            if finding.rule == "aa-socket-before-car-socket"
        ]

        self.assertEqual(1, len(waiting))
        self.assertEqual("INFO", waiting[0].severity)
        self.assertIn("waiting_for_car", waiting[0].detail)
        self.assertFalse(positive_cross_side_success(text))

    def test_positive_success_requires_every_end_to_end_signal(self):
        parts = {
            "bridge": (
                "12:00:01.000 I/Wpp: PHONE WPP SUMMARY attempt=5 outcome=connected "
                "missing=none timeline=TARGET_BT_CONNECTED@10>CAR_SOCKET@20>"
                "AA_SOCKET@30>BRIDGE_ESTABLISHED@40\n"
            ),
            "native": "12:00:02.000 I/AasdkSession: AA session started (native)\n",
            "render": "12:00:03.000 I/video: First frame rendered in 1200ms\n",
            "vflow": (
                "12:00:05.000 I/vflow: frames=42 idr=1 bytes=10000 window=2000ms\n"
                "12:00:07.000 I/vflow: frames=41 idr=0 bytes=9000 window=2000ms\n"
            ),
        }
        complete = "".join(parts.values())

        self.assertTrue(positive_cross_side_success(complete))
        for missing in parts:
            with self.subTest(missing=missing):
                incomplete = "".join(value for key, value in parts.items() if key != missing)
                self.assertFalse(positive_cross_side_success(incomplete))

    def test_native_sigabrt_in_paired_logcat_is_a_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            oal_path = Path(tmp) / "oal_2026-08-16_14-31-40.log"
            logcat_path = Path(tmp) / "logcat_2026-08-16_14-31-40.log"
            oal_path.write_text("14:32:05.498 I/session: ByeBye response received\n")
            logcat_path.write_text(
                "14:32:05.509 E/libc++abi: terminating due to uncaught exception "
                "of type std::system_error: thread::join failed: Resource deadlock would occur\n"
                "14:32:05.510 F/OAL-NativeCrash: NATIVE CRASH: signal=6 (SIGABRT)\n"
                "14:32:05.970 F/libc: Fatal signal 6 (SIGABRT)\n"
            )

            findings = check_transport_agnostic(str(oal_path))

        crashes = [finding for finding in findings if finding.rule == "native-crash"]
        self.assertEqual(1, len(crashes))
        self.assertEqual("FAIL", crashes[0].severity)
        self.assertIn("SIGABRT", crashes[0].detail)

    def test_unrelated_process_fatal_signal_is_not_attributed_to_oal(self):
        with tempfile.TemporaryDirectory() as tmp:
            oal_path = Path(tmp) / "oal_2026-08-16_14-31-40.log"
            logcat_path = Path(tmp) / "logcat_2026-08-16_14-31-40.log"
            oal_path.write_text("14:32:05.498 I/session: healthy\n")
            logcat_path.write_text(
                "14:32:05.970 88 99 F/libc: Fatal signal 11 (SIGSEGV), "
                "code 1 in tid 99, pid 88 (com.unrelated.app)\n"
            )

            findings = check_transport_agnostic(str(oal_path))

        self.assertFalse(any(finding.rule == "native-crash" for finding in findings))

    def test_sigquit_anr_is_not_misclassified_as_native_crash(self):
        with tempfile.TemporaryDirectory() as tmp:
            oal_path = Path(tmp) / "oal_2026-08-16_14-31-40.log"
            logcat_path = Path(tmp) / "logcat_2026-08-16_14-31-40.log"
            oal_path.write_text("14:32:05.498 I/session: stopping\n")
            logcat_path.write_text(
                "14:32:05.970 I/runtime: reacting to signal 3 (SIGQUIT)\n"
                "14:32:06.000 W/runtime: Wrote stack traces to tombstoned\n"
            )

            findings = check_transport_agnostic(str(oal_path))

        self.assertFalse(any(finding.rule == "native-crash" for finding in findings))
        self.assertTrue(any(finding.rule == "app-not-responding" for finding in findings))

    def test_old_logs_without_summary_markers_are_not_flagged_for_instrumentation(self):
        old_text = (
            "11:22:33.000 I/AaWirelessBt: Handshake complete\n"
            "11:22:34.000 I/AasdkSession: AA session started (native)\n"
        )

        self.assertEqual([], check_summary_text(old_text))
        self.assertFalse(positive_cross_side_success(old_text))


if __name__ == "__main__":
    unittest.main()
