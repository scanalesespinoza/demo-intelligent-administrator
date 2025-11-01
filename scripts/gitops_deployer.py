#!/usr/bin/env python3
"""GitOps-style deployment helper for Kubernetes/OpenShift clusters."""

from __future__ import annotations

import argparse
import logging
import os
import subprocess
import sys
from pathlib import Path
from typing import Callable, Iterable, List, Sequence

LOGGER = logging.getLogger("gitops_deployer")


class GitOpsError(RuntimeError):
    """Custom exception for GitOps deployment issues."""


def setup_logging() -> None:
    handler = logging.StreamHandler()
    handler.setFormatter(logging.Formatter("[%(levelname)s] %(message)s"))
    LOGGER.addHandler(handler)
    LOGGER.setLevel(logging.INFO)


def shutil_which_cached(command: str) -> str | None:
    from shutil import which

    return which(command)


def detect_client() -> str:
    for candidate in ("oc", "kubectl"):
        if shutil_which_cached(candidate):
            LOGGER.info("Using Kubernetes client: %s", candidate)
            return candidate
    raise GitOpsError("Neither 'oc' nor 'kubectl' was found in PATH.")


def discover_manifest_dirs(root_dir: Path) -> List[Path]:
    env_value = os.environ.get("GITOPS_MANIFEST_DIRS")
    if env_value:
        candidates = [Path(part.strip()) for part in env_value.split(",") if part.strip()]
    else:
        apps_dir = root_dir / "apps"
        candidates = sorted(p for p in apps_dir.rglob("k8s") if p.is_dir()) if apps_dir.exists() else []

    if not candidates:
        raise GitOpsError(
            "No manifest directories were discovered. Set GITOPS_MANIFEST_DIRS to a comma-separated list of directories."
        )

    validated: List[Path] = []
    for candidate in candidates:
        candidate_path = candidate if candidate.is_absolute() else root_dir / candidate
        if not candidate_path.is_dir():
            raise GitOpsError(f"Manifest directory not found: {candidate_path}")
        if not any(candidate_path.rglob("*.yml")) and not any(candidate_path.rglob("*.yaml")):
            raise GitOpsError(f"Manifest directory '{candidate_path}' does not contain any YAML resources.")
        validated.append(candidate_path.resolve())

    return validated


def run_for_each_dir(
    manifest_dirs: Sequence[Path],
    callback: Callable[[Path, Sequence[str]], int],
    extra_args: Sequence[str],
) -> int:
    exit_code = 0
    for directory in manifest_dirs:
        LOGGER.info("Processing %s", directory)
        result = callback(directory, extra_args)
        if result not in (0, None):
            exit_code = int(result)
    return exit_code


def run_client_command(client: str, args: Iterable[str]) -> subprocess.CompletedProcess:
    command = [client, *args]
    LOGGER.debug("Executing: %s", " ".join(str(part) for part in command))
    return subprocess.run(command, check=False)


def command_status(client: str, manifest_dirs: Sequence[Path], extra_args: Sequence[str]) -> int:
    LOGGER.info("Checking for drift against the desired state")
    exit_code = 0

    for directory in manifest_dirs:
        LOGGER.info("Diffing %s", directory)
        process = run_client_command(client, ["diff", "-f", str(directory), "--recursive", *extra_args])
        if process.returncode == 1:
            LOGGER.info("Differences detected for %s", directory)
            exit_code = 1
        elif process.returncode not in (0, 1):
            raise GitOpsError(f"Failed to diff manifests in {directory} (exit code {process.returncode})")

    if exit_code == 0:
        LOGGER.info("Cluster is in sync with the repository")
    else:
        LOGGER.warning("Drift detected. Run the 'sync' or 'normalize' command to reconcile.")

    return exit_code


def command_sync(client: str, manifest_dirs: Sequence[Path], extra_args: Sequence[str]) -> int:
    LOGGER.info("Applying desired state to the cluster")
    return run_for_each_dir(
        manifest_dirs,
        lambda directory, args: run_client_command(
            client, ["apply", "-f", str(directory), "--recursive", *args]
        ).returncode,
        extra_args,
    )


def command_normalize(client: str, manifest_dirs: Sequence[Path], extra_args: Sequence[str]) -> int:
    LOGGER.info("Reconciling the cluster to the repository state")
    try:
        command_status(client, manifest_dirs, extra_args)
    except GitOpsError:
        # Continue normalization even if status fails to allow correction via apply.
        pass
    return run_for_each_dir(
        manifest_dirs,
        lambda directory, args: run_client_command(
            client,
            [
                "apply",
                "-f",
                str(directory),
                "--recursive",
                "--server-side",
                "--force-conflicts",
                *args,
            ],
        ).returncode,
        extra_args,
    )


def command_uninstall(client: str, manifest_dirs: Sequence[Path], extra_args: Sequence[str]) -> int:
    LOGGER.info("Deleting managed resources")
    exit_code = 0
    for directory in reversed(manifest_dirs):
        LOGGER.info("Deleting manifests from %s", directory)
        process = run_client_command(
            client, ["delete", "-f", str(directory), "--recursive", "--ignore-not-found", *extra_args]
        )
        if process.returncode != 0:
            exit_code = process.returncode
    if exit_code == 0:
        LOGGER.info("Managed resources removed. The cluster should now match its pre-installation state.")
    return exit_code


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="GitOps-style deployment helper for Kubernetes/OpenShift clusters.",
        formatter_class=argparse.RawTextHelpFormatter,
    )

    subparsers = parser.add_subparsers(dest="command", required=True)
    help_map = {
        "status": "Show differences between the cluster and repository manifests.",
        "sync": "Apply repository manifests to the cluster.",
        "normalize": "Force the cluster to match the repository (server-side apply).",
        "uninstall": "Remove all managed resources from the cluster.",
    }

    for name, help_text in help_map.items():
        subparsers.add_parser(name, help=help_text)

    args, kubectl_options = parser.parse_known_args(argv)
    setattr(args, "kubectl_options", kubectl_options)

    return args


def main(argv: Sequence[str]) -> int:
    setup_logging()

    try:
        args = parse_args(argv)
        root_dir = Path(__file__).resolve().parent.parent
        client = detect_client()
        manifest_dirs = discover_manifest_dirs(root_dir)

        command_handlers = {
            "status": command_status,
            "sync": command_sync,
            "normalize": command_normalize,
            "uninstall": command_uninstall,
        }
        handler = command_handlers[args.command]
        extra_args = args.kubectl_options
        return handler(client, manifest_dirs, extra_args)
    except GitOpsError as error:
        LOGGER.error("%s", error)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
