# JGameServer Domain Language

JGameServer 是一个无区服的多人游戏服务。账号身份、在线角色、聊天房间和战斗分别拥有清晰的数据边界。

## Identity and Player

**LoginName**:
玩家登录时输入的稳定名称；规范化后与登录类型共同确定 AccountId，角色改名不改变它。
_Avoid_: Username, nickname, display name

**Account**:
玩家的登录身份；一个 Account 在当前游戏中只绑定一个 Player。
_Avoid_: Player, role, user character

**Player**:
Account 在游戏世界中的唯一角色，拥有角色资料、背包和其他玩家级数据。
_Avoid_: Account, login user

**DisplayName**:
Player 对其他玩家展示的可修改名称，与 LoginName 相互独立。
_Avoid_: LoginName, account name

## Social and Battle

**Chat Participant**:
已经登录且接入全局聊天能力的 Player。
_Avoid_: Account, connection

**Room**:
由一组 Chat Participants 组成的聊天空间；可以关联 Battle，但不拥有战斗状态。
_Avoid_: Battle, channel

**Battle**:
一次独立对局及其权威运行状态。
_Avoid_: Room, match queue

**Match Pool**:
等待系统撮合进入 Battle 的 Players 集合。
_Avoid_: Room, battle

## Runtime Boundaries

**Portal**:
客户端进入游戏前获取 Gate 地址的无状态入口。
_Avoid_: Gate, account service

**Gate**:
持有客户端长连接；每条连接对应一个 GateActor，负责认证前置处理和业务路由，不拥有账号或角色数据。
_Avoid_: Lobby, Gateway

**Lobby**:
拥有 Account 与 Player 在线生命周期及玩家级业务的游戏节点。
_Avoid_: Gate, Logic server

**Global**:
拥有在线玩家摘要、Match Pool、玩家到 Battle 的运行时目录、Chat Participants 与 Rooms 的单实例全局节点；状态不持久化。
_Avoid_: Chat server, player service

**Battle Location**:
Global 内存中 PlayerId 到 BattleId、Battle NodeId 的易失映射；重登录时通过 askAwait 查询。
_Avoid_: Redis route, persistent player state

**Insight**:
与游戏数据面隔离的管理控制面。
_Avoid_: GM server, Portal
