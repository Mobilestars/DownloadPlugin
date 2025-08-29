package de.scholle.download;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.ChatColor;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class DownloadPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    private List<String> blockedWorlds;
    private boolean logDownloads;
    private String logFileName;
    private int httpPort;
    private int publicPort;
    private long downloadExpiration;
    private String publicIp;

    private HttpServer httpServer;
    private File downloadsFolder;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        this.getCommand("download").setExecutor(this);
        this.getCommand("download").setTabCompleter(this);

        try {
            downloadsFolder = new File(getDataFolder(), "downloads");
            if (!downloadsFolder.exists()) downloadsFolder.mkdirs();

            clearAllZips();

            httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
            httpServer.createContext("/downloads", new FileHandler(downloadsFolder));
            httpServer.start();
            getLogger().info("HTTP-Server läuft auf Port " + httpPort + " (Ordner: " + downloadsFolder.getAbsolutePath() + ")");
        } catch (IOException e) {
            getLogger().severe("Konnte HTTP-Server nicht starten: " + e.getMessage());
        }

        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::cleanupDownloads, 20*60, 20*60);
    }

    private void clearAllZips() {
        if (downloadsFolder == null) return;
        File[] files = downloadsFolder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.getName().endsWith(".zip")) continue;
            if (file.delete()) {
                getLogger().info("Alte Download-Datei beim Serverstart gelöscht: " + file.getName());
            } else {
                getLogger().warning("Konnte alte Download-Datei beim Serverstart nicht löschen: " + file.getName());
            }
        }
    }

    @Override
    public void onDisable() {
        if (httpServer != null) {
            httpServer.stop(0);
            getLogger().info("HTTP-Server gestoppt.");
        }
    }

    private void loadSettings() {
        blockedWorlds = getConfig().getStringList("blocked-worlds");
        logDownloads = getConfig().getBoolean("log-downloads", true);
        logFileName = getConfig().getString("log-file", "downloads.log");
        httpPort = getConfig().getInt("http-port", 8080);
        publicPort = getConfig().getInt("public-port", httpPort);
        downloadExpiration = getConfig().getLong("download-expiration", 3600);
        publicIp = getConfig().getString("public-ip", "127.0.0.1");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cNur Spieler können diesen Befehl ausführen.");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage("§cBenutzung: /download <zahl>");
            return true;
        }
        String code = args[0];
        if (!code.matches("\\d+")) {
            sender.sendMessage("§cDie Zahl darf nur Ziffern enthalten.");
            return true;
        }
        Player player = (Player) sender;
        World world = player.getWorld();
        String worldName = world.getName();
        if (blockedWorlds.contains(worldName)) {
            player.sendMessage("§cDas Herunterladen dieser Welt ist nicht erlaubt!");
            return true;
        }
        player.sendMessage("§aStarte Welt-Export für: " + worldName);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                File worldFolder = new File(Bukkit.getWorldContainer(), worldName);
                String zipName = player.getName() + "." + worldName + "." + code + ".zip";
                File outFile = new File(downloadsFolder, zipName);
                zipWorld(worldFolder.toPath(), outFile.toPath());

                String baseUrl = publicIp;
                if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) baseUrl = "http://" + baseUrl;
                if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);

                String link;
                if (publicPort != 80 && publicPort != 443) {
                    link = baseUrl + ":" + publicPort + "/downloads/" + zipName;
                } else {
                    link = baseUrl + "/downloads/" + zipName;
                }

                TextComponent prefix = new TextComponent("Download-Link: ");
                prefix.setColor(ChatColor.GREEN);
                TextComponent linkComponent = new TextComponent(link);
                linkComponent.setColor(ChatColor.YELLOW);
                linkComponent.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, link));
                linkComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Klicken zum Herunterladen").create()));
                player.spigot().sendMessage(prefix, linkComponent);

                if (logDownloads) {
                    logDownload(player.getName(), worldName, zipName);
                }
            } catch (Exception e) {
                player.sendMessage("§cFehler beim Exportieren: " + e.getMessage());
                e.printStackTrace();
            }
        });
        return true;
    }

    private void zipWorld(Path sourceDirPath, Path zipFilePath) throws IOException {
        try (ZipOutputStream zs = new ZipOutputStream(Files.newOutputStream(zipFilePath))) {
            Files.walk(sourceDirPath)
                    .filter(path -> !Files.isDirectory(path))
                    .forEach(path -> {
                        ZipEntry zipEntry = new ZipEntry(sourceDirPath.relativize(path).toString());
                        try {
                            zs.putNextEntry(zipEntry);
                            Files.copy(path, zs);
                            zs.closeEntry();
                        } catch (IOException e) {
                            System.err.println("Fehler beim Packen: " + e.getMessage());
                        }
                    });
        }
    }

    private void logDownload(String playerName, String worldName, String zipName) {
        File logFile = new File(getDataFolder(), logFileName);
        try (FileWriter fw = new FileWriter(logFile, true);
             BufferedWriter bw = new BufferedWriter(fw);
             PrintWriter out = new PrintWriter(bw)) {
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            out.println("[" + time + "] " + playerName + " hat Welt '" + worldName + "' heruntergeladen: " + zipName);
        } catch (IOException e) {
            getLogger().warning("Konnte Download nicht loggen: " + e.getMessage());
        }
    }

    private void cleanupDownloads() {
        long now = System.currentTimeMillis();
        File[] files = downloadsFolder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.getName().endsWith(".zip")) continue;
            long ageInSeconds = (now - file.lastModified()) / 1000;
            if (ageInSeconds > downloadExpiration) {
                if (file.delete()) {
                    getLogger().info("Alte Download-Datei gelöscht: " + file.getName());
                } else {
                    getLogger().warning("Konnte alte Download-Datei nicht löschen: " + file.getName());
                }
            }
        }
    }

    static class FileHandler implements HttpHandler {
        private final File baseDir;
        public FileHandler(File baseDir) {
            this.baseDir = baseDir;
        }
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath().replace("/downloads/", "");
            File file = new File(baseDir, path);
            if (!file.exists() || file.isDirectory()) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.sendResponseHeaders(200, file.length());
            try (OutputStream os = exchange.getResponseBody();
                 FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, count);
                }
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (cmd.getName().equalsIgnoreCase("download")) {
            if (args.length == 1) {
                return Collections.singletonList("<zahl>");
            }
        }
        return Collections.emptyList();
    }
}
