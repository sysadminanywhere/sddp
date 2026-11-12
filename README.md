# SDDP Core Backend

Первая рабочая вертикаль платформы Spec-Driven Development Platform по `sddp_implementation_plan.md`.

## Запуск

Требуются Java 21, Maven и Docker. База запускается командой:

```text
docker compose up -d postgres
mvn spring-boot:run
```

По умолчанию приложение подключается к `jdbc:postgresql://localhost:5432/sddp`. Параметры можно переопределить через `SddP_DB_URL`, `SddP_DB_USERNAME` и `SddP_DB_PASSWORD`.

## Реализованный API

- `POST /api/v1/projects` — создать проект (`{"name":"billing"}`)
- `POST /api/v1/projects/import` — импортировать JSON Topology Snapshot
- `POST /api/v1/projects/{id}/services` — создать узел сервиса
- `POST /api/v1/projects/{id}/contracts` — сохранить версионированную JSON-схему
- `PUT /api/v1/projects/{id}/contracts/{contractId}` — создать совместимую новую версию контракта; существующие связи остаются привязаны к прежней версии
- `POST /api/v1/projects/{id}/connections` — сохранить связь, транспорт и mapping rules
- `POST /api/v1/topology/connections` — тот же контракт в формате `{projectId, connection}`
- `GET /api/v1/projects/{id}/topology?environment=DEV` — получить граф для UI
- `POST /api/v1/projects/{id}/ir?environment=DEV` — собрать и проверить IR-манифест
- `POST /api/v1/projects/{id}/snapshot?environment=DEV` — скачать JSON Topology Snapshot
- `POST /api/v1/codegen/generate` — сгенерировать Java proxy через FreeMarker; поле `plugin` опционально запускает Docker/Kubernetes executor
- `/actuator/health` — health check
- `/` — встроенный air-gapped графический дизайнер топологии

Миграции находятся в `src/main/resources/db/migration`. Схема хранит только метаданные, контракты и конфигурацию топологии; поля ПДн и пользовательские токены не предусмотрены.

## Проверка

```text
mvn test
```

Редактор графа использует локально собранные ресурсы React Flow. После изменения файлов в `frontend/` обновите их командами `npm ci` и `npm run build` из каталога `frontend`, затем соберите backend. В runtime загрузка пакетов из интернета не требуется. Перемещение узлов пока действует только в текущем сеансе браузера: координаты не сохраняются в snapshot.

Плагин Docker активен в профиле `docker` по умолчанию; для Kubernetes используйте `SPRING_PROFILES_ACTIVE=k8s`, `SDDP_K8S_NAMESPACE` и `SDDP_K8S_POLL_SECONDS`. Для выполнения Docker-плагинов процессу нужен доступ к Docker socket. Полный runtime acceptance с реальными PostgreSQL/Docker/Kubernetes стендами требует соответствующей инфраструктуры; локально проверены компиляция и unit-тесты.

Kubernetes-исполнитель передаёт IR через временный ConfigMap и считывает архив результатов из журнала pod. Образ плагина должен содержать `/bin/sh`, `plugin`, `tar`, `gzip`, `base64` и `tr`. ServiceAccount backend требует права создавать и удалять ConfigMap и Job, читать статус Job, список pod и их журналы. Для запрета сетевого доступа плагинов в кластере необходима отдельная NetworkPolicy; без неё air-gapped изоляция Job не гарантирована. Работа с реальным Kubernetes-кластером пока не проверена.

Встроенный REST-генератор использует `timeout_ms` для подключения и чтения, а `retries` — для повторов при ошибках сетевого доступа (значения по умолчанию: 5000 мс и 3 повтора). Повтор POST может привести к дублированию операции, если сервер обработал запрос до потери соединения; целевой API должен обеспечивать идемпотентность. MTLS и JWT встроенным генератором пока не поддерживаются и требуют подходящего плагина.

Сгенерированный REST-клиент использует `io.github.resilience4j:resilience4j-circuitbreaker:2.3.0`. Circuit breaker открывается после пяти вызовов с ошибками сетевого доступа и через 30 секунд допускает пробные вызовы; ошибка передаётся вызывающей стороне, а не подменяется успешным ответом. Эту зависимость необходимо включить в проект, использующий сгенерированный код.

Docker integration test запускается после локальной сборки образа `docker build --network=none -t sddp-plugin-fixture:local src/test/resources/docker-plugin` с переменной `SDDP_DOCKER_IT=true`. Для Docker Desktop на Windows можно указать `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine`: оба интеграционных сценария проверены через этот named pipe. На Linux используется стандартный Unix socket. Не открывайте Docker API в сеть без защиты: доступ к нему эквивалентен административному доступу к хосту.
