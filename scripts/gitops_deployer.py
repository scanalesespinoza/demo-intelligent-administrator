#!/usr/bin/env python3
"""GitOps-style deployment helper for Kubernetes/OpenShift clusters."""

from __future__ import annotations

import argparse
import logging
import os
import shutil
import subprocess
import sys
import tempfile
from contextlib import contextmanager
from pathlib import Path
from typing import Callable, Dict, Iterable, List, Optional, Sequence, Set, Tuple

LOGGER = logging.getLogger("gitops_deployer")
SUPPORTED_MODULES: Tuple[str, ...] = ("assistant-api", "assistant-ui")
ManifestEntry = Tuple[Path, Optional[str]]


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
    explicit_dirs = bool(env_value)

    def _contains_yaml(directory: Path) -> bool:
        for pattern in ("*.yml", "*.yaml"):
            try:
                next(directory.rglob(pattern))
                return True
            except StopIteration:
                continue
        return False

    for candidate in candidates:
        candidate_path = candidate if candidate.is_absolute() else root_dir / candidate
        if not candidate_path.is_dir():
            raise GitOpsError(f"Manifest directory not found: {candidate_path}")
        if not _contains_yaml(candidate_path):
            if explicit_dirs:
                raise GitOpsError(
                    f"Manifest directory '{candidate_path}' does not contain any YAML resources."
                )
            LOGGER.warning(
                "Skipping manifest directory %s because it does not contain any YAML resources.",
                candidate_path,
            )
            continue
        validated.append(candidate_path.resolve())

    if not validated:
        raise GitOpsError(
            "No manifest directories with YAML resources were discovered. Set GITOPS_MANIFEST_DIRS to a comma-separated list of directories."
        )

    return validated


def run_for_each_dir(
    manifest_info: Sequence[ManifestEntry],
    callback: Callable[[Path, Optional[str], Sequence[str]], int],
    extra_args: Sequence[str],
) -> int:
    exit_code = 0
    for directory, module in manifest_info:
        LOGGER.info("Processing %s", directory)
        result = callback(directory, module, extra_args)
        if result not in (0, None):
            exit_code = int(result)
    return exit_code


def detect_module(root_dir: Path, directory: Path) -> Optional[str]:
    """Return the application module associated with a manifest directory."""

    try:
        relative_parts = directory.relative_to(root_dir).parts
    except ValueError:
        return None

    for index, part in enumerate(relative_parts):
        if part in SUPPORTED_MODULES:
            next_index = index + 1
            if next_index < len(relative_parts) and relative_parts[next_index] == "k8s":
                if next_index + 1 == len(relative_parts):
                    return part
    return None


def compute_image_overrides() -> Dict[str, str]:
    """Build the mapping of module -> fully-qualified container image."""

    registry = os.environ.get("IADMIN_IMAGE_REGISTRY", "quay.io/iadmin").rstrip("/")
    tag = os.environ.get("IADMIN_IMAGE_TAG", "0.0.1")
    overrides: Dict[str, str] = {
        module: f"{registry}/{module}:{tag}"
        for module in SUPPORTED_MODULES
    }

    raw_overrides = os.environ.get("IADMIN_IMAGE_OVERRIDES")
    if raw_overrides:
        for entry in raw_overrides.split(","):
            if not entry.strip() or "=" not in entry:
                continue
            module, image = (segment.strip() for segment in entry.split("=", 1))
            if module:
                overrides[module] = image

    return overrides


def _split_image(image: str) -> Tuple[str, Optional[str]]:
    """Separate an image into name and optional tag without losing registry ports."""

    last_slash = image.rfind("/")
    name_with_tag = image if last_slash == -1 else image[last_slash + 1 :]
    if ":" in name_with_tag:
        base, tag = name_with_tag.split(":", 1)
        prefix = "" if last_slash == -1 else image[: last_slash + 1]
        return prefix + base, tag
    return image, None


@contextmanager
def manifest_invocation(
    directory: Path,
    module: Optional[str],
    image_overrides: Dict[str, str],
) -> Iterable[Tuple[str, str, bool]]:
    """Yield the kubectl flag/path tuple for the given directory."""

    if module is None or module not in image_overrides:
        yield ("-f", str(directory), True)
        return

    image = image_overrides[module]
    new_name, new_tag = _split_image(image)
    with tempfile.TemporaryDirectory() as temp_dir:
        temp_path = Path(temp_dir)
        manifests_path = temp_path / "manifests"
        shutil.copytree(directory, manifests_path, dirs_exist_ok=True)
        resource_entries: List[str] = []
        manifests_prefix = Path("manifests")
        kustomization_names = ("kustomization.yaml", "kustomization.yml", "Kustomization")

        kustomization_dirs: Set[Path] = set()

        for candidate in sorted(manifests_path.rglob("*")):
            if any(parent in kustomization_dirs for parent in candidate.parents):
                continue
            if candidate.is_dir():
                if any((candidate / name).exists() for name in kustomization_names):
                    relative = (manifests_prefix / candidate.relative_to(manifests_path)).as_posix()
                    resource_entries.append(relative)
                    kustomization_dirs.add(candidate)
                continue
            if candidate.suffix.lower() not in {".yaml", ".yml"}:
                continue
            relative = (manifests_prefix / candidate.relative_to(manifests_path)).as_posix()
            resource_entries.append(relative)

        if not resource_entries:
            raise GitOpsError(
                f"No YAML manifests were found in '{directory}'. Unable to generate kustomization overrides."
            )

        lines = [
            "apiVersion: kustomize.config.k8s.io/v1beta1",
            "kind: Kustomization",
            "resources:",
            *[f"  - ./{entry}" for entry in resource_entries],
            "images:",
            f"  - name: {module}",
            f"    newName: {new_name}",
        ]
        if new_tag:
            lines.append(f"    newTag: {new_tag}")
        lines.append("")
        (temp_path / "kustomization.yaml").write_text("\n".join(lines), encoding="utf-8")
        yield ("-k", str(temp_path), False)


def _apply_with_overrides(
    client: str,
    directory: Path,
    module: Optional[str],
    extra_args: Sequence[str],
    image_overrides: Dict[str, str],
    *,
    server_side: bool,
) -> int:
    with manifest_invocation(directory, module, image_overrides) as (flag, path, allow_recursive):
        command = ["apply", flag, path]
        if allow_recursive:
            command.append("--recursive")
        if server_side:
            command.extend(["--server-side", "--force-conflicts"])
        command.extend(extra_args)
        return run_client_command(client, command).returncode


def run_client_command(client: str, args: Iterable[str]) -> subprocess.CompletedProcess:
    command = [client, *args]
    LOGGER.debug("Executing: %s", " ".join(str(part) for part in command))
    return subprocess.run(command, check=False)


def command_status(
    client: str,
    manifest_info: Sequence[ManifestEntry],
    extra_args: Sequence[str],
    image_overrides: Dict[str, str],
) -> int:
    LOGGER.info("Checking for drift against the desired state")
    exit_code = 0

    for directory, module in manifest_info:
        LOGGER.info("Diffing %s", directory)
        with manifest_invocation(directory, module, image_overrides) as (flag, path, allow_recursive):
            command = ["diff", flag, path]
            if allow_recursive:
                command.append("--recursive")
            command.extend(extra_args)
            process = run_client_command(client, command)
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


def command_sync(
    client: str,
    manifest_info: Sequence[ManifestEntry],
    extra_args: Sequence[str],
    image_overrides: Dict[str, str],
) -> int:
    LOGGER.info("Applying desired state to the cluster")
    return run_for_each_dir(
        manifest_info,
        lambda directory, module, args: _apply_with_overrides(
            client, directory, module, args, image_overrides, server_side=False
        ),
        extra_args,
    )


def command_normalize(
    client: str,
    manifest_info: Sequence[ManifestEntry],
    extra_args: Sequence[str],
    image_overrides: Dict[str, str],
) -> int:
    LOGGER.info("Reconciling the cluster to the repository state")
    try:
        command_status(client, manifest_info, extra_args, image_overrides)
    except GitOpsError:
        # Continue normalization even if status fails to allow correction via apply.
        pass
    return run_for_each_dir(
        manifest_info,
        lambda directory, module, args: _apply_with_overrides(
            client, directory, module, args, image_overrides, server_side=True
        ),
        extra_args,
    )


def command_uninstall(
    client: str,
    manifest_info: Sequence[ManifestEntry],
    extra_args: Sequence[str],
    image_overrides: Dict[str, str],
) -> int:
    LOGGER.info("Deleting managed resources")
    exit_code = 0
    for directory, module in reversed(manifest_info):
        LOGGER.info("Deleting manifests from %s", directory)
        with manifest_invocation(directory, module, image_overrides) as (flag, path, allow_recursive):
            command = ["delete", flag, path]
            if allow_recursive:
                command.append("--recursive")
            command.append("--ignore-not-found")
            command.extend(extra_args)
            process = run_client_command(client, command)
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
        manifest_info: List[ManifestEntry] = [
            (directory, detect_module(root_dir, directory)) for directory in manifest_dirs
        ]
        image_overrides = compute_image_overrides()

        command_handlers = {
            "status": command_status,
            "sync": command_sync,
            "normalize": command_normalize,
            "uninstall": command_uninstall,
        }
        handler = command_handlers[args.command]
        extra_args = args.kubectl_options
        return handler(client, manifest_info, extra_args, image_overrides)
    except GitOpsError as error:
        LOGGER.error("%s", error)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
