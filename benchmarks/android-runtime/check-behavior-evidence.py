#!/usr/bin/env python3
"""Independent expectations for the product engine on the predeclared Android fixture."""
import json
import sys
from pathlib import Path


def check(matched, mutant):
    assert json.loads((matched / "comparison.json").read_text())["status"] == "SCENARIO_MATCHED"
    assert json.loads((mutant / "comparison.json").read_text())["status"] == "DIFFERENT"
    for root in (matched, mutant):
        for role in ("original", "candidate"):
            report = json.loads((root / role / "report.json").read_text())
            assert report["status"] == "COMPLETED" and report["cleanupComplete"] is True
            assert report["evidence"] == "OBSERVED" and report["uid"] >= 10000
            assert report["environment"]["androidApi"] == "30"
            assert len(report["snapshots"]) == 2
            if role == "original":
                for step, text in enumerate(("Result: 42", "Result: 44")):
                    assert any(node["attributes"].get("text") == text for node in report["snapshots"][step]["ui"])
            assert "RuntimeFixture" in report["appLog"] and "session started" in report["appLog"]
            assert any("shared_prefs/runtime-fixture.xml" in row["path"] for row in report["filesAfter"])
            assert any(row["change"] == "CREATED" and "runtime-fixture.xml" in row["after"]["path"] for row in report["fileChanges"])
            assert report["permissions"]["android.permission.INTERNET"] is True
            assert sum(report["deniedPackets"].values()) > 0, "network attempt must reach the session's denial rule"
            assert report["snapshots"][0]["totalPssKb"] > 0
            assert not report["crashExcerpts"]
            assert any(c["name"] == "API calls and network payloads" and c["status"] == "NOT_IMPLEMENTED" for c in report["collectors"])
    print("Product runtime engine: UI, log, storage, memory, permission, denial and mutation checks pass")


if __name__ == "__main__":
    check(*(Path(p) for p in sys.argv[1:]))
