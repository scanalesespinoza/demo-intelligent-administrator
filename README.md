# iAdmin

## Guía de instalación

### 1. Prerrequisitos

Antes de empezar, asegúrate de contar con lo siguiente:

- **Sistema operativo**: Linux, macOS o Windows 10/11 con WSL2.
- **Git**: para clonar el repositorio.
- **Java Development Kit (JDK) 21**: Quarkus requiere como mínimo Java 21. Configura la variable de entorno `JAVA_HOME` apuntando a esta instalación.
- **Maven 3.9+**: puedes usar la instalación global o el *wrapper* incluido (`./mvnw`).
- **Docker** *(opcional)*: necesario solo si deseas construir imágenes contenedorizadas o generar ejecutables nativos dentro de un contenedor.

### 2. Obtener el código fuente

1. Clona el repositorio:
   ```bash
   git clone https://github.com/tu-organizacion/demo-intelligent-administrator.git
   cd demo-intelligent-administrator
   ```
2. (Opcional) Selecciona la rama correspondiente a la versión que deseas instalar.

### 3. Configuración inicial

iAdmin está compuesto por dos aplicaciones Quarkus: `assistant-api` (backend) y `assistant-ui` (frontend). Cada módulo incluye un archivo `application.properties` con valores por defecto que puedes ajustar según tu entorno.

- **Variables sensibles y externas**:
  - `OIDC_ISSUER` y `OIDC_SECRET`: habilita autenticación OIDC descomentando las líneas correspondientes en `apps/assistant-api/src/main/resources/application.properties` y exportando estas variables antes de iniciar la aplicación.
  - `LLM_ENDPOINT`: establece la URL del modelo de lenguaje que utilizará el backend. Por defecto apunta a `http://localhost/mock-llm`.
  - `LLM_API_KEY`: define la clave utilizada para autenticar las llamadas contra el proveedor del modelo de lenguaje. Si se omite, las peticiones se enviarán sin cabecera `Authorization`.
- **Punto de enlace del API**:
  - En `apps/assistant-ui/src/main/resources/application.properties`, la propiedad `assistant.api.url` apunta al servicio desplegado en Kubernetes. Para ejecución local, sobreescribe este valor con la URL local del backend (por ejemplo, `http://localhost:8080`).
  - Puedes sobreescribir cualquier propiedad de Quarkus mediante variables de entorno en mayúsculas (por ejemplo, `ASSISTANT_API_URL`).

### 4. Compilación y verificación

Ejecuta una compilación completa desde la raíz del repositorio para descargar dependencias y verificar que ambos módulos se construyan correctamente:

```bash
./mvnw clean install
```

Si prefieres usar Maven global, reemplaza `./mvnw` por `mvn`.

### 5. Ejecución en modo de desarrollo

Inicia cada módulo en terminales separadas para desarrollar y probar la aplicación localmente:

1. **Backend (`assistant-api`)**
   ```bash
   cd apps/assistant-api
   ./mvnw quarkus:dev
   ```
   El API se expone en `http://localhost:8080` y la Dev UI en `http://localhost:8080/q/dev/`.

2. **Frontend (`assistant-ui`)**
   ```bash
   cd apps/assistant-ui
   ./mvnw quarkus:dev
   ```
   La interfaz se sirve en `http://localhost:8081`.

Mientras el frontend esté en ejecución, verifica que `assistant.api.url` apunte al backend local.

### 6. Empaquetado para despliegue

Desde cada módulo puedes generar artefactos listos para producción:

- **JAR ejecutable**
  ```bash
  ./mvnw package
  java -jar target/quarkus-app/quarkus-run.jar
  ```

- **JAR "uber"** (todas las dependencias incluidas en un único archivo)
  ```bash
  ./mvnw package -Dquarkus.package.jar.type=uber-jar
  java -jar target/*-runner.jar
  ```

- **Binario nativo** (requiere GraalVM o Docker)
  ```bash
  ./mvnw package -Dnative
  # o, sin GraalVM instalado localmente
  ./mvnw package -Dnative -Dquarkus.native.container-build=true
  ```

### 7. Construcción de imágenes de contenedor

Ambos módulos incluyen Dockerfiles en `src/main/docker/`. Por ejemplo, para construir y ejecutar la imagen JVM del backend:

```bash
cd apps/assistant-api
docker build -f src/main/docker/Dockerfile.jvm -t iadmin/assistant-api:latest .
docker run --rm -p 8080:8080 iadmin/assistant-api:latest
```

Repite el proceso en `assistant-ui` ajustando el puerto a `8081`.

### 8. Verificación de salud

Con las aplicaciones en ejecución:

- Comprueba el backend accediendo a `http://localhost:8080/q/health`.
- Comprueba el frontend visitando `http://localhost:8081` en el navegador.

### 9. Siguientes pasos

- Configura despliegues en Kubernetes usando los manifiestos en `apps/assistant-api/k8s/`.
- Integra tu proveedor OIDC y modelo de lenguaje en entornos de prueba o producción.
- Automatiza la ejecución de `./mvnw test` o `./mvnw verify` en tu pipeline CI/CD para validar futuras modificaciones.

### Instalación automatizada en Linux/WSL

Si prefieres automatizar estos pasos en entornos Linux o WSL, ejecuta el script `scripts/install_iadmin.py`.

```bash
python3 scripts/install_iadmin.py --help
```

De forma predeterminada el script verifica los prerrequisitos y ejecuta `./mvnw clean install`.
Puedes combinar parámetros para clonar el repositorio (`--clone`), generar artefactos adicionales
(`--uber-jar`, `--build-native`) o construir las imágenes de contenedor (omítelo con `--skip-containers`).
