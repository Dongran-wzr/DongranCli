# DongranCLI 发布包模板

这个目录用于生成可以直接分发给他人的发布包。

## 一键打包

### Windows

```bat
release\package-release.cmd
```

### macOS / Linux

```bash
chmod +x release/package-release.sh
./release/package-release.sh
```

## 产物目录

打包完成后会生成：

```text
release/dist/
└─ DongranCLI-<version>/
   ├─ DongranCli.jar
   ├─ install.cmd
   ├─ install.sh
   ├─ .env.example
   └─ README.md
```

版本号从项目根目录 `pom.xml` 的 `<version>` 自动读取。

## 给使用者的说明

- 安装 Java 21+
- 把 `.env.example` 复制为 `.env` 并填写自己的 API Key
- Windows 运行 `install.cmd`
- macOS/Linux 运行 `install.sh`
- 重新打开终端后执行 `dongran --version`

## 安全提醒

- 不要把真实 `.env` 放进发布包
- 不要在示例文件里写真实 key
