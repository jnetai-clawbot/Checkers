# Checkers

An Android checkers game made by **jnetai.com**.

## Modes
- **Play vs AI** — offline computer opponent (Easy / Medium / Hard)
- **2 Player** — pass & play on one device, offline
- **Online Multiplayer** — peer-to-peer over the internet (WebRTC via public
  Google STUN + openrelay TURN):
  - **Host a game** — share the code or QR with an opponent
  - **Join a game** — enter or scan the code
  - **Quick Match** — random matchmaking: first looks for an existing host,
    otherwise auto-hosts a room, looping until a player is found

## Settings
- AI difficulty
- Worldwide rules: **UK / Europe (default)**, US / American, International
- Per-player timer (off / 3 / 5 / 10 minutes)

## High Scores
Beat the AI to earn a score — fewer moves and a faster time rank higher.

## Build
Releases are built with GitHub Actions on a tagged push (`v*`). Never build locally.

```bash
git tag v1.0.0 && git push origin master --tags
```

The release workflow signs the APK with a stable per-app keystore so in-place
updates never require uninstalling the previous version.