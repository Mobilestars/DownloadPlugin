# DownloadPlugin for Minecraft

DownloadPlugin is a Minecraft server plugin that allows players to download the world they are currently in as a ZIP file. This is especially useful for large building servers or personal worlds.

## Features

- Download any world with `/download <number>` command.
- Only allows downloads from worlds that are not blocked.
- Generates ZIP files named as `<playerName>.<worldName>.<number>.zip`.
- Provides clickable download links in chat.
- Logs all downloads if enabled.
- Automatically deletes old downloads based on expiration time.
- Deletes all ZIPs on server start to prevent storage issues.
- Supports internal HTTP port and public port via tunnels (like Playit.gg).

## Commands
/download <number>



- `<number>`: A numeric code chosen by the player to make the ZIP filename unique. Example: `/download 12345`
- Blocked worlds cannot be downloaded.

## Configuration (`config.yml`)

```yaml
blocked-worlds:
  - world_nether
  - world_the_end
log-downloads: true
log-file: downloads.log
http-port: 8100        # Internal port where the HTTP server runs
public-port: 27429     # Public port that players use to access downloads
download-expiration: 3600  # Expiration time in seconds for automatic deletion
public-ip: "147.185.221.23" # Server's public IP address or domain
