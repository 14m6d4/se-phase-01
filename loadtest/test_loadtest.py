"""Small assert checks for k6 allocation, Kaggle SQL, and source bundles."""
import ast
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import tempfile
import zipfile
from pathlib import Path, PurePosixPath
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]


def test_seat_slices():
    source = (ROOT / "loadtest/k6/lib/seat-allocation.js").read_text()
    check = source + """
import assert from 'node:assert/strict';
const all = Array.from({ length: 50_280 }, (_, i) => i);
const slices = partitionSlots(all, BOOK_MAX_VUS);
assert.equal(slices.length, 12);
assert.deepEqual(slices.map((slice) => slice.length), Array(12).fill(4_190));
assert.deepEqual(slices.flat(), all);
assert.ok(slices.every((slice) => slice.length >= BOOK_MIN_SEATS_PER_VU));
"""
    subprocess.run(["node", "--input-type=module", "-e", check], cwd=ROOT, check=True)


def test_contention_verifier():
    notebook = json.loads((ROOT / "loadtest/kaggle.ipynb").read_text())
    cells = {cell.get("id"): cell for cell in notebook["cells"]}
    psql_tree = ast.parse("".join(cells["c06"]["source"]))
    psql_function = next(node for node in psql_tree.body if isinstance(node, ast.FunctionDef)
                         and node.name == "psql")
    run_tree = ast.parse("".join(cells["c04"]["source"]))
    run_function = next(node for node in run_tree.body if isinstance(node, ast.FunctionDef)
                        and node.name == "run")
    verifier_tree = ast.parse("".join(cells["c12"]["source"]))
    verifier_function = next(node for node in verifier_tree.body if isinstance(node, ast.FunctionDef)
                             and node.name == "verify_contention")

    commands = []
    responses = [
        SimpleNamespace(returncode=0, stdout="PostgreSQL test\n"),
        SimpleNamespace(returncode=0, stdout="10|10\n"),
        SimpleNamespace(returncode=0, stdout="10|9\n"),
        SimpleNamespace(returncode=1, stdout="ERROR: relation booked_seat does not exist; db-test-password gh-test-token\n"),
    ]

    def subprocess_run(command, **kwargs):
        commands.append((command, kwargs))
        return responses.pop(0)

    run_namespace = {
        "subprocess": SimpleNamespace(PIPE=-1, STDOUT=-2, run=subprocess_run),
        "GITHUB_TOKEN": "gh-test-token",
        "DB_PASSWORD": "db-test-password",
    }
    exec(compile(ast.Module(body=[run_function], type_ignores=[]), "kaggle-c04-run", "exec"), run_namespace)
    psql_namespace = {"run": run_namespace["run"]}
    exec(compile(ast.Module(body=[psql_function], type_ignores=[]), "kaggle-c06-psql", "exec"), psql_namespace)
    assert psql_namespace["psql"]("SELECT version()").startswith("PostgreSQL")
    assert "-d postgres" in commands[-1][0]  # bootstrap/system-catalog callers retain their original database

    with tempfile.TemporaryDirectory() as directory:
        results = Path(directory)
        summary = results / "summary.json"
        details = results / "contention.json"
        details.write_text(json.dumps({"showtimeId": "01234567-89ab-cdef-0123-456789abcdef"}))
        summary.write_text(json.dumps({"metrics": {
            "bookings_created": {"values": {"count": 10}},
            "booking_conflicts": {"values": {"count": 100}},
            "seat_conflicts": {"values": {"count": 90}},
            "pending_customer_conflicts": {"values": {"count": 10}},
        }}))
        namespace = {
            "RESULTS": results,
            "contention_verification_passed": None,
            "json": json,
            "re": re,
            "psql": psql_namespace["psql"],
        }
        code = ast.Module(body=[verifier_function], type_ignores=[])
        exec(compile(code, "kaggle-c12-verifier", "exec"), namespace)
        namespace["verify_contention"](summary, details, 0)
        report = (results / "contention-verification.txt").read_text()
        assert report.startswith("PASS ") and "duplicate rows: 0" in report
        assert "409 Seat already taken: 90" in report
        assert "409 Customer already has Pending Booking: 10" in report
        assert "-d cinema" in commands[-1][0]

        namespace["verify_contention"](summary, details, 0)
        assert (results / "contention-verification.txt").read_text().startswith("FAIL ")
        assert "-d cinema" in commands[-1][0]

        namespace["verify_contention"](summary, details, 0)
        report = (results / "contention-verification.txt").read_text()
        assert "ERROR: relation booked_seat does not exist" in report
        assert "db-test-password" not in report and "gh-test-token" not in report


def test_source_bundle_paths():
    notebook = json.loads((ROOT / "loadtest/kaggle.ipynb").read_text())
    cell = next(cell for cell in notebook["cells"] if cell.get("id") == "c08")
    tree = ast.parse("".join(cell["source"]))
    function = next(node for node in ast.walk(tree) if isinstance(node, ast.FunctionDef)
                    and node.name == "safe_bundle_path")
    namespace = {"PurePosixPath": PurePosixPath}
    exec(compile(ast.Module(body=[function], type_ignores=[]), "kaggle-c08-validator", "exec"), namespace)
    safe_path = namespace["safe_bundle_path"]
    assert safe_path("loadtest/seed.sql").as_posix() == "loadtest/seed.sql"
    for name in ("../escape", "/absolute", "..\\escape", "src/../escape", ".env.local", "target/classes/a"):
        try:
            safe_path(name)
        except RuntimeError:
            pass
        else:
            raise AssertionError(f"unsafe bundle path accepted: {name}")


def test_source_bundle_inputs():
    notebook = json.loads((ROOT / "loadtest/kaggle.ipynb").read_text())
    cell = next(cell for cell in notebook["cells"] if cell.get("id") == "c08")
    tree = ast.parse("".join(cell["source"]))
    branch = next(node for node in tree.body if isinstance(node, ast.If)
                  and isinstance(node.test, ast.Name) and node.test.id == "CODE_BUNDLE")
    setup = ast.Module(body=[*tree.body[:3], branch], type_ignores=[])

    def load(bundle, destination):
        zip_reads = []
        commands = []

        def open_zip(*args, **kwargs):
            zip_reads.append(Path(args[0]))
            return zipfile.ZipFile(*args, **kwargs)

        def run(command, **kwargs):
            commands.append(command)
            return "0123456789abcdef" * 2 + "01234567" if command == "git rev-parse HEAD" else ""

        namespace = {
            "CODE_BUNDLE": str(bundle) if bundle is not None else None, "SRC": destination,
            "hashlib": hashlib, "json": json, "os": os, "Path": Path,
            "PurePosixPath": PurePosixPath, "re": re, "shutil": shutil,
            "stat": stat, "zipfile": SimpleNamespace(ZipFile=open_zip),
            "zip_reads": zip_reads, "commands": commands, "run": run,
            "GITHUB_TOKEN": "test-token", "REPO": "owner/repo", "GIT_REF": "main",
        }
        exec(compile(setup, "notebook-c08-bundle-loader", "exec"), namespace)
        return namespace

    def make_source(root):
        files = {
            "pom.xml": b"<project/>",
            "mvnw": b"#!/bin/sh\n",
            ".mvn/wrapper/maven-wrapper.properties": b"distributionUrl=test\n",
            "loadtest/seed.sql": b"SELECT 1;\n",
            "loadtest/report.py": b"print('report')\n",
            "src/Main.java": b"class Main {}\n",
            "loadtest-bundle.json": json.dumps({
                "snapshotMode": "working-tree", "sourceCommit": "0123456789abcdef" * 2 + "01234567",
            }).encode(),
        }
        for name, content in files.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
            if name == "mvnw":
                path.chmod(0o755)
        return files

    def make_zip(root, archive, extra=None):
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as output:
            for path in sorted(root.rglob("*")):
                if path.is_file() and not path.is_symlink():
                    output.write(path, path.relative_to(root).as_posix())
            if extra:
                output.writestr(*extra)

    def expect_rejected(bundle, destination, message):
        try:
            load(bundle, destination)
        except RuntimeError as error:
            assert message in str(error), str(error)
        else:
            raise AssertionError(f"unsafe or incomplete source bundle accepted: {bundle}")

    with tempfile.TemporaryDirectory() as directory:
        temp = Path(directory)
        expanded = temp / "dataset" / "source"
        files = make_source(expanded)
        from_directory = load(expanded, temp / "directory-checkout")
        assert from_directory["COMMIT"] == "0123456789abcdef" * 2 + "01234567"
        assert from_directory["SOURCE_PROVENANCE"] == f"full source snapshot based on {from_directory['COMMIT']}"
        assert from_directory["CODE_BUNDLE_HASH_KIND"].startswith("expanded tree:")
        assert from_directory["files"] == set(files)
        assert (from_directory["SRC"] / "mvnw").stat().st_mode & 0o111
        assert all((from_directory["SRC"] / name).read_bytes() == content for name, content in files.items())
        assert from_directory["zip_reads"] == []
        repeated = load(expanded, temp / "directory-checkout-repeat")
        assert from_directory["CODE_BUNDLE_SHA256"] == repeated["CODE_BUNDLE_SHA256"]

        archive = temp / "source.zip"
        make_zip(expanded, archive)
        from_zip = load(archive, temp / "zip-checkout")
        assert from_zip["COMMIT"] == from_directory["COMMIT"]
        assert from_zip["SOURCE_PROVENANCE"] == from_directory["SOURCE_PROVENANCE"]
        assert from_zip["files"] == from_directory["files"]
        assert from_zip["CODE_BUNDLE_HASH_KIND"] == "exact ZIP file bytes"
        assert from_zip["CODE_BUNDLE_SHA256"] == hashlib.sha256(archive.read_bytes()).hexdigest()
        assert all((from_zip["SRC"] / name).read_bytes() == content for name, content in files.items())
        assert (from_zip["SRC"] / "mvnw").stat().st_mode & 0o111
        assert from_zip["zip_reads"] == [archive]

        git_path = load(None, temp / "git-checkout")
        assert git_path["SOURCE_PROVENANCE"] == f"Git ref main at {git_path['COMMIT']}"
        assert git_path["CODE_BUNDLE_SHA256"] == "none"
        assert git_path["CODE_BUNDLE_HASH_KIND"] == "not used (Git clone)"
        assert git_path["commands"] == [
            "git clone --quiet https://x-access-token:test-token@github.com/owner/repo.git "
            f"{git_path['SRC']}",
            "git checkout --quiet main",
            "git remote set-url origin https://github.com/owner/repo.git",
            "git rev-parse HEAD",
        ]

        wrong_root = temp / "dataset"
        expect_rejected(wrong_root, temp / "wrong-root-checkout", "expanded source root")

        symlink_tree = temp / "symlink-source"
        make_source(symlink_tree)
        (symlink_tree / "link").symlink_to(expanded / "pom.xml")
        expect_rejected(symlink_tree, temp / "symlink-checkout", "symlink")

        for name, message in (("../escape", "Unsafe path"), (".env.local", "omit build output and secrets"),
                              ("secrets/token", "omit build output and secrets")):
            bad_zip = temp / "unsafe.zip"
            make_zip(expanded, bad_zip, (name, b"secret"))
            expect_rejected(bad_zip, temp / "unsafe-checkout", message)

        symlink_zip = temp / "symlink.zip"
        link = zipfile.ZipInfo("link")
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        make_zip(expanded, symlink_zip, (link, b"../outside"))
        expect_rejected(symlink_zip, temp / "symlink-zip-checkout", "symlink or special file")


if __name__ == "__main__":
    test_seat_slices()
    test_contention_verifier()
    test_source_bundle_paths()
    test_source_bundle_inputs()
    print("PASS  allocation; SQL verifier; Git fallback; ZIP/directory bundles, deterministic hash, and path/symlink rejection")
