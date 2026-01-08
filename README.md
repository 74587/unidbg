# 编译步骤

## 环境要求

- JDK 8
- Maven 3.9.12

## 本地编译

```bash
mvn -v
```

```bash
mvn -DskipTests package
```

编译完成后，Jar 位于 `target/fqnovel.jar`。

## 运行示例

```bash
java -jar target/fqnovel.jar
```
