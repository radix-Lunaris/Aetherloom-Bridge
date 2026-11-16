# Aetherloom Bridge

用于 [Aetherloom](https://github.com/radix-Lunaris/Aetherloom) Host 漂移流程的 Forge 客户端桥接 Mod。GPLv3，独立源码与构建仓库。

目标环境：Minecraft **1.20.1**、Forge **47.4.22**、Java **17**。普通外部启动时不配置控制入口，Mod保持惰性；它不是独立服务器，也不负责云存档、P2P隧道或软件账号登录。

## 职责

- 当受管单人世界真正就绪后，自动开放LAN并报告实际端口、世界和游戏UUID。
- Player通过Forge服务发现取得握手信息后自动加入目标本地代理地址。
- 报告ready / joined / saved / error生命周期；受管会话离开后关闭Minecraft，便于控制端继续处理最终保存。
- 通过控制端成员绑定的离线身份，在Forge入服协商阶段保持自定义UUID；拒绝错误控制回执。

UUID/NBT文件修复由Aetherloom单独的Java辅助模块实现，其中NBT基础类来自MIT许可的shared-playerdata；**不包含在本Mod源码或JAR里**。Bridge只处理运行时游戏身份与控制事件。

## 构建

设置JAVA_HOME为JDK17，Windows运行 `powershell -ExecutionPolicy Bypass -File build-release.ps1`；Linux可先 `bash gradlew build --no-daemon`。最终普通Mod产物为 `release/aetherloom-bridge-0.1.0.jar` 和校验和；测试Fixture JAR不会发行。依赖需要访问Forge/Mojang官方仓库。

安装到已准备游戏目录的mods下。独立安装本Mod并不会自动开始共享流程，需由Aetherloom或实现相同控制协议的可信控制端提供配置。

## 控制协议

控制端通过环境变量 `AETHERLOOM_BRIDGE_FILE` 指向本次启动的JSON文件：

```json
{"endpoint":"http://127.0.0.1:本机端口","token":"单次随机凭据","world":"绝对世界路径","identity":"游戏UUID","inspect":false}
```

Player额外提供 `join: "127.0.0.1:本地游戏代理端口"`，并使用隔离配置目录。Host由启动器进入选定世界，Bridge不扫描saves。inspect模式不开放LAN、不占公共Host。

- 仅允许127.0.0.1 HTTP控制端；请求使用Bearer随机凭据，不使用软件账号令牌。
- GET根返回stop指令；POST根包含event、world、uuid和port，控制端须验证世界、身份、状态和长度。
- Host入服协商通过GET `/identity/{玩家名称}` 获取该受控连接已登记的UUID；映射必须来自经授权的软件成员，控制端不能按昵称猜测归属。
- saved事件仅表示游戏保存生命周期完成，控制端仍须等待文件锁释放并核验租约／代次／父版本，才允许提交权威世界。网络断联或缺少保存回执不得视为成功。

公开协议不包含任何真实端口凭据示例。GameProfile UUID设置依赖当前目标版本的Authlib字段，其他游戏版本需单独适配和验收。

## 来源与许可

本仓库从Aetherloom的bridge模块独立提取，保留独立测试夹具。原应用仍可固定版本引入此源码；不引入HMCL/PCL源码。

Bridge代码使用GNU GPLv3（见LICENSE）。Gradle包装器来自Forge官方MDK，独立保留其Apache-2.0许可LICENSE.gradle；Forge/Minecraft/Gradle依赖保持各自许可。真实游戏、双机、NAT、更多Mod兼容性应结合控制端分别验证。

## 0.1.1: external-login rooms

Control configuration may specify `auth_mode=littleskin`. In this mode LAN uses
online authentication; the embedding launcher must inject authlib-injector on
both Host and Player with the same provider. Verified profile UUIDs must match
the software-authorized peer identity; signed texture properties are preserved.
No identity rewriting is performed for verified profiles. Host in-memory login
is distinguished from remote players. Default remains offline; no account or
credential handling is included in this Mod.
