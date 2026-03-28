# 𓁟 Thoth Tool — Track Progress

A plugin tool for Thoth agents to track, update, and review progress on multi-step tasks. Automatically keeps the current task and progress visible in the system prompt so the agent doesn't repeat work or loop.

## Requirements

- Java 25+ (JDK)
- Thoth 0.1.0+ (with plugin system)

### 1. Configure

Add the tool to your `thoth.conf`:

```json
{
  ...,
  "tool": [
    {
      "name": "track-progress",
      "version": "0.1.0-SNAPSHOT",
      "enabled": true
    },
    ...
  ]
}
```

### 2. Place the JAR

Copy `build/libs/track-progress-0.1.0-SNAPSHOT.jar` into Thoth's plugin directory `providers/`.

## Usage

Once installed and configured, Thoth will automatically discover and load the Track Progress tool. You can verify it's active via Thoth's monitoring socket or logs.

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE).
