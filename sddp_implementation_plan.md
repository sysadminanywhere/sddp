# ТЕХНИЧЕСКОЕ ЗАДАНИЕ И ПЛАН РЕАЛИЗАЦИИ: SPEC-DRIVEN DEVELOPMENT PLATFORM (SDDP)

## 1. ОБЩИЕ АРХИТЕКТУРНЫЕ ТРЕБОВАНИЯ
*   **Парадигма:** Contract-First (Спецификация — единственный источник правды).
*   **Тип поставки:** Изолированное Self-Hosted / On-Premise решение. Полный Air-Gapped (0% внешних сетевых запросов во внешнюю сеть при рантайме).
*   **Стек ядра:** Java 21 (Использовать Records, Pattern Matching, Виртуальные потоки), Spring Boot 4.x, PostgreSQL, Apache Kafka (для внутренней шины логов сборки).
*   **Безопасность (152-ФЗ):** Платформа оперирует исключительно метаданными, схемами контрактов и конфигурациями топологии. Хранение ПДн запрещено.
*   **Жизненный цикл топологии:** Изменения статичны (Compile-time). Запрещено динамическое переключение каналов в рантайме. Изменение схемы/транспорта = выпуск нового релиза артефакта.

## 2. ИНТЕРФЕЙС ДИЗАЙНЕРА (CONTROL PLANE UI)
*   **Визуальный редактор графа:** Реализуется в виде SPA (React/Vue с библиотекой React Flow / Vue Flow). Отображает сервисы как узлы (Nodes), а связи между ними как направленные стрелки (Edges).
*   **Типы узлов:**
    *   *Core Service:* Иммутабельный сервис разрабатываемого продукта.
    *   *Legacy/External Service:* Существующая внешняя система заказчика с фиксированным API.
*   **Связи и Байндинги:** Каждая стрелка между узлами жестко определяет контракт данных (ссылка на OpenAPI/AsyncAPI спецификацию) и транспортный протокол среды выполнения (REST, gRPC, Kafka).
*   **Управление маппингом полей:** Интерактивный UI для сопоставления полей (`Field Mapping Editor`) при связи Core-сервиса с Legacy-системой. Результат сохраняется в декларативный JSON-манифест правил трансформации.
*   **Импорт/Экспорт проекта (Topology Snapshot):** Функция выгрузки всей конфигурации в один монолитный JSON-архив. Позволяет софтверной компании спроектировать архитектуру в своем контуре, экспортировать слепок и импортировать его на изолированном On-Premise стенде заказчика для мгновенного развертывания.

## 3. СПЕЦИФИКАЦИЯ ПРОМЕЖУТОЧНОГО ПРЕДСТАВЛЕНИЯ: INTERMEDIATE REPRESENTATION (IR JSON)
Ядро системы преобразует граф из БД в плоский JSON-файл абстрактного контракта (`ir.json`). Этот файл передается на вход плагинам.

### Строгая JSON-схема IR-манифеста:
```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["system_name", "environment", "generated_at", "services"],
  "properties": {
    "system_name": { "type": "string" },
    "environment": { "type": "string", "enum": ["DEV", "STAGE", "PROD"] },
    "generated_at": { "type": "string", "format": "date-time" },
    "services": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["service_name", "is_external", "outbound_connections"],
        "properties": {
          "service_name": { "type": "string" },
          "is_external": { "type": "boolean" },
          "outbound_connections": {
            "type": "array",
            "items": {
              "type": "object",
              "required": ["client_name", "target_service_name", "transport_type", "connection_settings", "methods"],
              "properties": {
                "client_name": { "type": "string" },
                "target_service_name": { "type": "string" },
                "transport_type": { "type": "string", "enum": ["REST", "GRPC", "KAFKA", "RABBITMQ"] },
                "connection_settings": {
                  "type": "object",
                  "required": ["host", "port", "target_endpoint"],
                  "properties": {
                    "host": { "type": "string" },
                    "port": { "type": "integer" },
                    "target_endpoint": { "type": "string" },
                    "timeout_ms": { "type": "integer", "default": 5000 },
                    "retries": { "type": "integer", "default": 3 },
                    "security_type": { "type": "string", "enum": ["NONE", "MTLS", "JWT"] }
                  }
                },
                "methods": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["method_name", "pattern", "request_schema", "response_schema"],
                    "properties": {
                      "method_name": { "type": "string" },
                      "pattern": { "type": "string", "enum": ["REQUEST_RESPONSE", "PUB_SUB"] },
                      "request_schema": { "type": "object" },
                      "response_schema": { "type": "object" }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
```

## 4. СТРУКТУРА БАЗЫ ДАННЫХ (POSTGRESQL DDL)
```sql
-- Таблица проектов / систем
CREATE TABLE projects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Глобальный каталог микросервисов
CREATE TABLE services (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID REFERENCES projects(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    is_external BOOLEAN DEFAULT FALSE,
    UNIQUE(project_id, name)
);

-- Таблица версионированных контрактов (Бизнес-логика, схемы JSONB)
CREATE TABLE contracts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID REFERENCES projects(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    version VARCHAR(50) NOT NULL,
    request_schema JSONB NOT NULL,
    response_schema JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, name, version)
);

-- Топология соединений и байндингов (Слой инфраструктуры)
CREATE TABLE topology_connections (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID REFERENCES projects(id) ON DELETE CASCADE,
    environment VARCHAR(50) NOT NULL,
    source_service_id UUID REFERENCES services(id) ON DELETE CASCADE,
    target_service_id UUID REFERENCES services(id) ON DELETE CASCADE,
    contract_id UUID REFERENCES contracts(id),
    transport_type VARCHAR(50) NOT NULL,
    connection_settings JSONB NOT NULL,
    mapping_rules JSONB, -- Декларативные правила трансформации полей для внешних систем
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Реестр CLI/Docker плагинов генерации
CREATE TABLE codegen_plugins (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    language VARCHAR(50) NOT NULL,
    delivery_type VARCHAR(20) NOT NULL,
    executable_target TEXT NOT NULL,
    is_active BOOLEAN DEFAULT TRUE
);
```

## 5. СПЕЦИФИКАЦИЯ REST API CORE-БЭКЕНДА
```
POST /api/v1/projects                  ➔ Создать новый проект/систему
GET  /api/v1/projects/{id}/topology     ➔ Получить структуру узлов и связей для отрисовки в UI Дизайнера
POST /api/v1/topology/connections      ➔ Сохранить новую связь, выбранный транспорт и правила маппинга
POST /api/v1/projects/{id}/snapshot    ➔ Сгенерировать и выгрузить Topology Snapshot (JSON-слепок)
POST /api/v1/projects/import           ➔ Импортировать слепок системы на изолированном стенде заказчика
POST /api/v1/codegen/generate          ➔ Запустить генерацию кода (сборка IR JSON и вызов плагинов)
```

## 6. ДЕКЛАРАТИВНАЯ СХЕМА МАППИНГА ДАННЫХ ДЛЯ ЛЕГАСИ-СИСТЕМ
Пример структуры JSONB в поле `mapping_rules`, определяющий правила автоматической трансформации данных в изолированных сгенерированных прокси-адаптерах:
```json
{
  "source_contract": "internal.order.created.v1",
  "target_contract": "external.bank.payment.v3",
  "transformations": [
    { "type": "DIRECT_COPY", "from": "$.order.id", "to": "$.transaction_uuid" },
    { "type": "CONCATENATE", "from": ["$.user.first_name", "$.user.last_name"], "to": "$.payment_details.receiver_name", "separator": " " },
    { "type": "HARDCODED_VALUE", "value": "RUB", "to": "$.currency" }
  ]
}
```

## 7. ПОШАГОВЫЙ ПЛАН РЕАЛИЗАЦИИ МОДУЛЕЙ СИСТЕМЫ

### Модуль 1: Core REST API & Валидатор Контрактов (Spring Boot 4.x)
1. Реализовать REST-контроллеры согласно спецификации API.
2. **Бизнес-логика проверки совместимости (Backward Compatibility Engine):**
   * Создать сервис, который при изменении `contracts.request_schema` или `response_schema` сравнивает старую и новую JSON-схемы (использовать библиотеку `networknt/json-schema-validator`).
   * **Правила блокировки:** Блокировать коммит (выдавать ошибку `400 Bad Request`), если из схемы ответа удалены поля, или если в схему запроса добавлены новые обязательные (`required`) поля.

### Модуль 2: Движок Агрегации Топологии (IR Builder)
1. Написать компонент `IntermediateRepresentationBuilder`, который принимает `project_id` и `environment`.
2. Выполнить SQL-запрос, собирающий граф связей из `topology_connections` и сопоставляющий их со схемами из `contracts`.
3. Сформировать Java-объект (маппинг на Jackson) и валидировать его против JSON-схемы IR, описанной в Разделе 3.

### Модуль 3: Изолированный Подслой Выполнения Плагинов (DOCKER / KUBERNETES)
Создать фабрику `PluginExecutionFactory`, которая считывает `delivery_type` из `codegen_plugins` и запускает процесс генерации в изолированной песочнице.

#### Реализация для сред Docker (Локальный запуск / Тесты):
1. Использовать библиотеку `docker-java`.
2. Пробросить локальный сокет `/var/run/docker.sock` в контейнер Core-бэкенда.
3. Процесс генерации:
   * Создать временную общую папку на хосте: `/tmp/sddp_shared/run_ID`.
   * Записать туда `ir.json`. Создать пустую папку `/output`.
   * Вызвать Docker API для запуска контейнера плагина.

#### Реализация для сред Kubernetes (Enterprise On-Premise):
1. Использовать официальный `io.kubernetes:client-java`.
2. Реализовать оркестрацию через создание краткосрочной **Kubernetes Job**.
3. Создать `PersistentVolumeClaim` или использовать встроенный `emptyDir` хоста.
4. Программный манифест запуска Job из Java через `BatchV1Api`.
5. Реализовать цикл ожидания: опрашивать статус Job через `readNamespacedJobStatus`. При переходе в `Succeeded` — вычитать сгенерированные файлы из общего Volume и очистить ресурсы.

## 8. ЭТАЛОННЫЙ ШАБЛОН ГЕНЕРАЦИИ ПРОКСИ-АДАПТЕРА (APACHE FREEMARKER)
Данный `.ftl` шаблон используется плагином для автоматического рендеринга полностью преднастроенных, отказоустойчивых клиентов на стеке **Spring Boot 4.x (Java 21)**:

```io
package ${package_name};

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Optional;

@Service
public class ${client_name} {

    private static final Logger log = LoggerFactory.getLogger(${client_name}.class);
    private final RestClient restClient;

    public ${client_name}(
        RestClient.Builder builder,
        @Value("${topology.services.${target_service_name}.url}") String targetUrl
    ) {
        this.restClient = builder.baseUrl(targetUrl).build();
    }

    <#list methods as method>
    @Retry(name = "${target_service_name}")
    @CircuitBreaker(name = "${target_service_name}", fallbackMethod = "fallback${method.method_name}")
    public ${method.response_dto} ${method.method_name}(${method.request_dto} request) {
        log.info("Sending request to ${target_service_name} via RestClient. TraceId context injected.");

        return restClient.post()
            .uri("${method.target_endpoint}")
            .body(request)
            .retrieve()
            .body(${method.response_dto}.class);
    }

    public ${method.response_dto} fallback${method.method_name}(${method.request_dto} request, Throwable t) {
        log.error("Target service ${target_service_name} unavailable. Graceful degradation triggered. Error: {}", t.getMessage());
        // Возврат пустой или дефолтной структуры, изолирующей сбой внешней системы
        return new ${method.response_dto}(true, "Fallback: Target system offline");
    }
    </#list>
}
```

## 9. НАСТРОЙКИ КОНФИГУРАЦИИ MAVEN (POM.XML) ЯДРА ПЛАТФОРМЫ
Спецификация зависимостей для сборки Core-бэкенда на **Spring Boot 4.x** с поддержкой Kubernetes API, реактивного стека и работы с типами `JSONB` в PostgreSQL:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.0-M1</version>
        <relativePath/>
    </parent>

    <groupId>ru.sddp.platform</groupId>
    <artifactId>sddp-core-backend</artifactId>
    <version>1.0.0</version>
    <name>sddp-core-backend</name>
    <description>Core Backend Contract-First SDDP Platform (Spring Boot 4.x, Java 21)</description>

    <properties>
        <java.version>21</java.version>
        <kubernetes.client.version>21.0.0</kubernetes.client.version>
        <json.schema.validator.version>1.5.1</json.schema.validator.version>
    </properties>

    <dependencies>
        <!-- Веб-инфраструктура и оркестрация -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Работа с базой данных PostgreSQL через Spring Data JPA -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>

        <!-- Драйвер PostgreSQL с поддержкой JSONB -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Интеграция Hibernate с Jackson для маппинга JSONB в Java Records -->
        <dependency>
            <groupId>com.vladmihalcea</groupId>
            <artifactId>hibernate-types-60</artifactId>
            <version>2.11.1</version>
        </dependency>

        <!-- Внутренний транспорт (Kafka) для логов сборки -->
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka</artifactId>
        </dependency>

        <!-- Официальный Java-клиент Kubernetes для программного деплоя K8s Jobs -->
        <dependency>
            <groupId>io.kubernetes</groupId>
            <artifactId>client-java</artifactId>
            <version>${kubernetes.client.version}</version>
        </dependency>
        <dependency>
            <groupId>io.kubernetes</groupId>
            <artifactId>client-java-extended</artifactId>
            <version>${kubernetes.client.version}</version>
        </dependency>

        <!-- Валидатор JSON-схем на обратную совместимость (Backward Compatibility) -->
        <dependency>
            <groupId>com.networknt</groupId>
            <artifactId>json-schema-validator</artifactId>
            <version>${json.schema.validator.version}</version>
        </dependency>

        <!-- Встроенный движок FreeMarker для кодогенерации -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-freemarker</artifactId>
        </dependency>

        <!-- Мониторинг, метрики и Health-Checks (Kubernetes Probes) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- Тестовая инфраструктура -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>

    <repositories>
        <repository>
            <groupId>org.springframework.milestones</groupId>
            <artifactId>spring-boot-milestones</artifactId>
            <url>https://repo.spring.io/milestone</url>
            <snapshots>
                <enabled>false</enabled>
            </snapshots>
        </repository>
    </repositories>
    <pluginRepositories>
        <pluginRepository>
            <groupId>org.springframework.milestones</groupId>
            <artifactId>spring-boot-milestones</artifactId>
            <url>https://repo.spring.io/milestone</url>
            <snapshots>
                <enabled>false</enabled>
            </snapshots>
        </pluginRepository>
    </pluginRepositories>
</project>
```

---

## 10. ЧЕК-ЛИСТ ПРИЕМКИ ДЛЯ ИИ-АГЕНТА
Перед сдачей кода ИИ-агент должен верифицировать систему по следующим критериям:
* [ ] **Compile-time иммутабельность:** В сгенерированном Java-коде отсутствуют какие-либо компоненты динамического переключения или опроса внешних серверов. Всё работает на статических свойствах Spring.
* [ ] **Отсутствие ПДн:** Ни одна таблица в базе данных PostgreSQL не содержит полей для ФИО, e-mail, телефонов или токенов пользователей. Только схемы метаданных.
* [ ] **Clean Code & Спецификация ошибок:** Если внешний Docker-контейнер плагина завершается с кодом, отличным от `0`, Java-бэкенд перехватывает поток `System.err` контейнера и выбрасывает кастомное исключение `PluginGenerationException` с отображением лога ошибки.
* [ ] **Kubernetes-native готовность:** Код управления K8s Jobs вынесен в отдельный изолированный Spring-профиль (`@Profile("k8s")`), чтобы локально проект можно было запускать без наличия Кубернетеса через стандартный профиль Docker (`@Profile("docker")`).
