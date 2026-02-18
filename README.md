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
## docker
本地缓存版
```bash
docker run -d --name fqnovel --restart=unless-stopped -p 9999:9999 gxmandppx/unidbg-fq:latest
```
postgresql缓存版
```bash
docker run -d --name fqnovel --restart=unless-stopped -p 9999:9999 -e DB_URL='postgresql://user:password@ip:端口/db' gxmandppx/unidbg-fq:latest
```

## ☕ 支持与赞赏

如果你觉得这个项目对你有帮助，欢迎打赏支持！你的支持是我持续维护和更新的最大动力。
仅支持L站积分。
[![Sponsor Mengying](https://img.shields.io/badge/Sponsor-Mengying-ea4aaa?style=for-the-badge&logo=heart&logoColor=white)](https://shop.mengying.me/pay)

👉 [点击这里前往赞赏页面](https://shop.mengying.me/pay)

## 免责声明

**本项目仅供学习交流使用，使用时请遵守相关法律法规。用户需自行承担由此引发的任何法律责任和风险。程序的作者及项目贡献者不对因使用本程序所造成的任何损失、损害或法律后果负责！**
