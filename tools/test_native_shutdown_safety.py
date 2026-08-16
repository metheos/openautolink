#!/usr/bin/env python3
"""Regression checks for native AA shutdown thread ownership."""

from pathlib import Path
import unittest


SOURCE = (
    Path(__file__).resolve().parents[1]
    / "app/src/main/cpp/jni_session.cpp"
)


def function_body(source: str, signature: str) -> str:
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 0
    for index in range(opening, len(source)):
        char = source[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return source[opening + 1 : index]
    raise AssertionError(f"unterminated function: {signature}")


class NativeShutdownSafetyTest(unittest.TestCase):
    def test_bye_bye_response_never_stops_on_io_service_thread(self):
        body = function_body(
            SOURCE.read_text(encoding="utf-8"),
            "void JniSession::onByeByeResponse(",
        )

        self.assertIn(
            "auto self = shared_from_this();",
            body,
            "the detached stop worker must retain the session lifetime",
        )
        self.assertEqual(
            1,
            body.count("std::thread([self] { self->stop(); }).detach();"),
            "ByeBye response must dispatch the real stop on one detached worker",
        )
        self.assertNotRegex(
            body,
            r"(?m)^\s*stop\(\);\s*$",
            "direct stop() self-joins ioThread_ and aborts with EDEADLK",
        )


if __name__ == "__main__":
    unittest.main()
