# myapp

一个用 Java 写的命令行 Todo List 小软件。

## 特性

- 添加 / 列出 / 完成 / 删除 / 重命名 / 清空任务
- 数据以纯文本保存到 `~/.myapp/tasks.tsv`（可用环境变量 `MYAPP_HOME` 覆盖目录）
- 无第三方运行时依赖，仅用 JDK

## 构建

```bash
mvn package
```

打包后会生成 `target/myapp-0.1.0.jar`（含 Main-Class，可直接 `java -jar` 运行）。

## 使用

```bash
java -jar target/myapp-0.1.0.jar add 买牛奶
java -jar target/myapp-0.1.0.jar list
java -jar target/myapp-0.1.0.jar done 1
java -jar target/myapp-0.1.0.jar rm 1
java -jar target/myapp-0.1.0.jar rename 2 写周报
java -jar target/myapp-0.1.0.jar clear
```

## 测试

```bash
mvn test
```
