# 服务器性能监控插件

### MC端使用方法：
1. 从GitHub Releases下载插件jar文件，或者在项目根目录下运行`./gradlew.bat build`来自己构建。
2. 将插件jar文件放入MC服务器的`plugins`目录，并重启服务器。
3. 编辑`plugins/ServerPerfMon/config.yml`，配置monitor-server-url为你的监控&Bot服务器的地址。

### 监控服务器使用方法：
1. 克隆本仓库
2. 进入`monitor-server`目录
3. 运行`npm install`安装依赖
4. 创建或编辑`monitor-server/config.json`文件，记得删掉`//`后面的部分 避免错误！
```json
{
    "httpPort": 8080, // 与MC端通信的HTTP端口
    "botServerPort": 8081, // 与Bot通信的反向WebSocket端口
    "botServerPath": "/onebot", // 与Bot通信的反向WebSocket路径
    "botAccessToken": "Replace_Me_!$@#" // 与Bot通信的访问令牌
}

```
4. 运行`npm start`启动

### QQ Bot使用方法（以NapCat为例）：

在NapCat Web UI中，点击左侧“网络配置”，点击上方“新建”，选择“WebSocket客户端”，填写以下信息：
- 地址：ws://监控服务器地址:监控服务器botServerPort/监控服务器botServerPath（例如，若你的监控服务器使用上面的默认配置：ws://127.0.0.1:8081/onebot）
- Token: 监控服务器config.json中的botAccessToken。（例如，若你的监控服务器使用上面的默认配置：Replace_Me_!$@#，为了安全最好改掉，或者控制防火墙不暴露8081端口）

**注意，NapCat Docker受Docker容器限制，不能通过127.0.0.1访问监控服务器，需要使用docker的网桥功能，填网桥IP（默认为172.17.0.1）。**