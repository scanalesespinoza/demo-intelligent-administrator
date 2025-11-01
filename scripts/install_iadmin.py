#!/usr/bin/env python3
"""Script de instalación automatizada para iAdmin en Linux, WSL o Windows.

Este script sigue los pasos descritos en la guía de instalación del README.
Se enfoca en automatizar la preparación del entorno, la obtención del código,
la compilación y la generación de artefactos para despliegue.
"""
from __future__ import annotations

import argparse
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Iterable, List, Optional

REPO_URL = "https://github.com/tu-organizacion/demo-intelligent-administrator.git"
DEFAULT_BRANCH = "main"


class InstallationError(RuntimeError):
    """Error personalizado para fallos en el proceso de instalación."""


def is_wsl() -> bool:
    """Determina si el script se ejecuta dentro de WSL."""
    if "WSL_DISTRO_NAME" in os.environ:
        return True
    try:
        with open("/proc/version", "r", encoding="utf-8") as version_file:
            return "microsoft" in version_file.read().lower()
    except OSError:
        return False


def is_windows() -> bool:
    """Indica si el sistema operativo detectado es Windows."""
    return platform.system().lower() == "windows"


def check_platform() -> None:
    """Garantiza que el script se ejecute en Linux, WSL o Windows."""
    system = platform.system().lower()
    if system not in {"linux", "windows"}:
        raise InstallationError(
            "Este script está diseñado para ejecutarse en Linux, WSL o Windows. "
            f"Sistema detectado: {platform.system()}"
        )


def ensure_command(command: str) -> bool:
    """Verifica si un comando está disponible en la ruta."""
    return shutil.which(command) is not None


def run_command(
    command: List[str],
    cwd: Optional[Path] = None,
    extra_env: Optional[dict[str, str]] = None,
) -> None:
    """Ejecuta un comando mostrando su salida en tiempo real."""
    print(f"\n→ Ejecutando: {' '.join(command)}" + (f" (cwd={cwd})" if cwd else ""))
    env = os.environ.copy()
    if extra_env:
        env.update(extra_env)
    try:
        subprocess.run(command, check=True, cwd=cwd, env=env)
    except FileNotFoundError as exc:
        raise InstallationError(
            "No se pudo ejecutar el comando requerido. "
            "Verifica que esté instalado y disponible en el PATH: "
            f"{command[0]}"
        ) from exc
    except subprocess.CalledProcessError as exc:
        raise InstallationError(
            f"La ejecución del comando {' '.join(command)} falló con código {exc.returncode}."
        ) from exc


def clone_repository(destination: Path, branch: str, force: bool) -> Path:
    """Clona el repositorio en la ruta indicada si aún no existe."""
    if destination.exists():
        if (destination / ".git").exists() and not force:
            print(f"Repositorio existente detectado en {destination}. Se reutiliza.")
            return destination
        if not force:
            raise InstallationError(
                f"El directorio {destination} ya existe. Usa --force-clone para reemplazarlo."
            )
        print(f"Eliminando el directorio {destination} por solicitud de --force-clone.")
        shutil.rmtree(destination)

    run_command(["git", "clone", "--branch", branch, REPO_URL, str(destination)])
    return destination


def detect_container_runtime() -> Optional[str]:
    """Devuelve el runtime de contenedores disponible (docker o podman)."""
    for candidate in ("docker", "podman"):
        if ensure_command(candidate):
            return candidate
    return None


def has_maven_wrapper(path: Path) -> bool:
    """Comprueba si en la ruta indicada existe el Maven Wrapper."""
    return any((path / candidate).exists() for candidate in ("mvnw", "mvnw.cmd"))


def check_prerequisites(
    require_docker: bool, wrapper_available: bool = False
) -> Optional[str]:
    """Valida que las dependencias básicas estén instaladas."""
    missing: list[str] = []
    for command in ("git", "java"):
        if not ensure_command(command):
            missing.append(command)
    container_runtime: Optional[str] = None
    if require_docker:
        container_runtime = detect_container_runtime()
        if container_runtime is None:
            missing.append("docker o podman")

    has_maven = ensure_command("mvn")
    if not has_maven:
        if wrapper_available:
            print(
                "ℹ️  Maven no está instalado globalmente. Se utilizará el Maven Wrapper (./mvnw"
                " o mvnw.cmd) incluido en el repositorio."
            )
        else:
            missing.append("mvn (o el Maven Wrapper ./mvnw / mvnw.cmd)")

    if missing:
        raise InstallationError(
            "Faltan dependencias requeridas: " + ", ".join(missing) +
            ". Instálalas manualmente e intenta de nuevo."
        )

    java_home = os.environ.get("JAVA_HOME")
    if not java_home:
        print(
            "⚠️  Advertencia: la variable JAVA_HOME no está definida. "
            "Quarkus requiere JDK 21 o superior."
        )

    return container_runtime


def ensure_repo_path(path: Path) -> Path:
    """Devuelve la ruta del repositorio y valida su contenido."""
    if not path.exists():
        raise InstallationError(f"La ruta {path} no existe.")
    if not (path / "pom.xml").exists():
        raise InstallationError(
            f"La ruta {path} no parece contener el repositorio de iAdmin (pom.xml faltante)."
        )
    return path.resolve()


def get_maven_command(repo_path: Path) -> List[str]:
    """Devuelve el comando apropiado para ejecutar Maven o el Maven Wrapper."""
    wrapper_unix = repo_path / "mvnw"
    wrapper_windows = repo_path / "mvnw.cmd"

    if is_windows():
        if wrapper_windows.exists():
            return ["cmd", "/c", str(wrapper_windows)]
        return ["mvn"]

    if wrapper_unix.exists():
        return [str(wrapper_unix)]

    return ["mvn"]


def run_maven_in_modules(
    repo_path: Path,
    goals: Iterable[str],
    extra_args: Optional[Iterable[str]] = None,
    maven_command: Optional[List[str]] = None,
) -> None:
    """Ejecuta Maven en los módulos assistant-api y assistant-ui."""
    modules = [repo_path / "apps" / "assistant-api", repo_path / "apps" / "assistant-ui"]
    command_prefix = maven_command or get_maven_command(repo_path)
    args = list(goals)
    if extra_args:
        args.extend(extra_args)

    for module in modules:
        run_command([*command_prefix, *args], cwd=module)


def build_containers(repo_path: Path, container_runtime: str) -> None:
    """Construye las imágenes de contenedor para ambos módulos."""
    dockerfile_mapping = {
        "assistant-api": "Dockerfile.jvm",
        "assistant-ui": "Dockerfile.jvm",
    }
    podman_on_wsl = container_runtime == "podman" and is_wsl()
    if podman_on_wsl:
        print(
            "Se detectó Podman ejecutándose en WSL. Se usará CONTAINERS_EVENTS_BACKEND=file "
            "para evitar advertencias de journald durante la construcción."
        )
    for module, dockerfile in dockerfile_mapping.items():
        module_path = repo_path / "apps" / module
        image_tag = f"iadmin/{module}:latest"
        command = [container_runtime]
        if podman_on_wsl:
            # En algunos entornos WSL, Podman intenta notificar eventos vía journald,
            # lo que genera errores por la ausencia de systemd. Forzamos el backend
            # de eventos a "file" mediante la opción de la CLI además de la variable
            # de entorno para cubrir todas las versiones de Podman.
            command.append("--events-backend=file")
        command.extend(
            [
                "build",
                "-f",
                f"src/main/docker/{dockerfile}",
                "-t",
                image_tag,
                ".",
            ]
        )
        run_command(
            command,
            cwd=module_path,
            extra_env={"CONTAINERS_EVENTS_BACKEND": "file"} if podman_on_wsl else None,
        )


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Instalador automatizado de iAdmin")
    parser.add_argument(
        "--clone",
        action="store_true",
        help="Clona el repositorio antes de ejecutar el resto de pasos.",
    )
    parser.add_argument(
        "--branch",
        default=DEFAULT_BRANCH,
        help="Rama a clonar cuando se usa --clone (por defecto: main).",
    )
    parser.add_argument(
        "--destination",
        type=Path,
        default=Path.cwd(),
        help="Directorio donde se clonará o buscará el repositorio.",
    )
    parser.add_argument(
        "--force-clone",
        action="store_true",
        help="Reemplaza el directorio de destino si ya existe al clonar.",
    )
    parser.add_argument(
        "--skip-containers",
        action="store_true",
        help="Omite la construcción de imágenes de contenedor.",
    )
    parser.add_argument(
        "--build-native",
        action="store_true",
        help="Genera binarios nativos además de los empaquetados JVM.",
    )
    parser.add_argument(
        "--uber-jar",
        action="store_true",
        help="Genera también artefactos tipo uber-jar en cada módulo.",
    )
    parser.add_argument(
        "--only-verify",
        action="store_true",
        help="Ejecuta únicamente la compilación/verificación sin empaquetar.",
    )
    return parser.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    try:
        args = parse_args(argv)
        check_platform()
        wrapper_available = args.clone or has_maven_wrapper(args.destination)
        container_runtime = check_prerequisites(
            require_docker=not args.skip_containers,
            wrapper_available=wrapper_available,
        )

        repo_path = args.destination
        if args.clone:
            repo_path = clone_repository(args.destination, args.branch, args.force_clone)

        repo_path = ensure_repo_path(repo_path)
        print(f"Repositorio localizado en {repo_path}")

        maven_command = get_maven_command(repo_path)

        # Paso 4 de la guía: compilación y verificación.
        run_command([*maven_command, "clean", "install"], cwd=repo_path)

        if args.only_verify:
            print("Se solicitó únicamente verificación. Instalación finalizada.")
            return 0

        # Paso 6 de la guía: empaquetado para despliegue.
        run_maven_in_modules(repo_path, ["package"], maven_command=maven_command)

        if args.uber_jar:
            run_maven_in_modules(
                repo_path,
                ["package"],
                ["-Dquarkus.package.jar.type=uber-jar"],
                maven_command=maven_command,
            )

        if args.build_native:
            run_maven_in_modules(
                repo_path,
                ["package"],
                ["-Dnative"],
                maven_command=maven_command,
            )

        # Paso 7: construcción de imágenes de contenedor.
        if not args.skip_containers:
            if container_runtime is None:
                raise InstallationError(
                    "No se encontró un runtime de contenedores (docker o podman)."
                )
            build_containers(repo_path, container_runtime)
        else:
            print("Se omitió la construcción de contenedores por configuración.")

        # Paso 8: verificación de salud (recomendaciones finales).
        print(
            "\n✔ Instalación completada. Inicia los servicios en modo desarrollo con:\n"
            "  cd apps/assistant-api && ./mvnw quarkus:dev\n"
            "  cd apps/assistant-ui && ./mvnw quarkus:dev\n"
            "Luego visita http://localhost:8080/q/health y http://localhost:8081 para verificar."
        )
        if is_wsl():
            print("Detectado WSL: recuerda exponer los puertos desde WSL si accedes desde Windows.")
        return 0
    except InstallationError as error:
        print(f"\n✖ Error durante la instalación: {error}")
        return 1
    except KeyboardInterrupt:
        print("\nInstalación interrumpida por el usuario.")
        return 130


if __name__ == "__main__":
    sys.exit(main())
