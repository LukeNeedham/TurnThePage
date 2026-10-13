# Выпуск Листай в SourceCraft

Актуальная процедура находится в [проектном навыке `listai-release`](../.agents/skills/listai-release/SKILL.md).
Он описывает проверки перед выпуском, сборку и проверку подписи постоянным ключом,
push, CI, тег и публикацию APK. Секреты и новый ключ в репозитории не хранятся.

Репозиторий: [avstaim/listai](https://sourcecraft.dev/avstaim/listai), remote
`origin`, основная ветка `main`. Версия берётся из `gradle.properties`; текущий
стабильный выпуск — `1.6`, `versionCode = 29`, тег `v1.6`.

## Ссылки, закреплённые на тегах

- [README 1.6](https://sourcecraft.dev/avstaim/listai/browse/README.md?rev=tag%3Av1.6)
- [Release notes 1.6](https://sourcecraft.dev/avstaim/listai/browse/docs/releases/1.6.md?rev=tag%3Av1.6)
- [Исторические notes 0.9](https://sourcecraft.dev/avstaim/listai/browse/docs/releases/0.9.md?rev=tag%3Av0.9)

APK и контрольная сумма публикуются на странице
[SourceCraft releases/v1.6](https://sourcecraft.dev/avstaim/listai/releases/v1.6).
Фактические проверки перечислены в release notes и подтверждаются CI конкретного
release-коммита. Физические устройства в текущую область проверки не входят.
