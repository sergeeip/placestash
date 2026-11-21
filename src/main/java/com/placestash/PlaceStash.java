package com.placestash;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.data.Lightable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class PlaceStash extends JavaPlugin implements Listener, TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** x,y,z = the spawner. The candle cake sits directly on top (y + 1). */
    private record Stash(String world, int x, int y, int z) {}

    private final List<Stash> stashes = new ArrayList<>();
    private File stashFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        stashFile = new File(getDataFolder(), "stashes.yml");
        loadStashes();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("placestash");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getLogger().info("PlaceStash enabled. Active stashes: " + stashes.size());
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("placestash.use")) {
            say(sender, "no-permission");
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }

        if (args.length == 0) {
            placeStash(p);
        } else if (args[0].equalsIgnoreCase("list")) {
            if (stashes.isEmpty()) {
                say(p, "list-empty");
            } else {
                for (int i = 0; i < stashes.size(); i++) {
                    Stash s = stashes.get(i);
                    p.sendMessage(MM.deserialize("<gray>#" + (i + 1) + " <white>" + s.world()
                            + " " + s.x() + ", " + s.y() + ", " + s.z()));
                }
            }
        } else if (args[0].equalsIgnoreCase("remove")) {
            removeNearest(p);
        } else {
            p.sendMessage(MM.deserialize("<red>Usage: /placestash [list | remove]"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        return args.length == 1 ? List.of("list", "remove") : List.of();
    }

    // --------------------------------------------------------------- placement

    private void placeStash(Player p) {
        int minD = Math.max(2, getConfig().getInt("min-depth", 8));
        int maxD = Math.max(minD, getConfig().getInt("max-depth", 30));
        int radius = Math.max(0, getConfig().getInt("horizontal-radius", 12));
        int attempts = Math.max(1, getConfig().getInt("attempts", 300));

        World w = p.getWorld();
        int px = p.getLocation().getBlockX();
        int py = p.getLocation().getBlockY();
        int pz = p.getLocation().getBlockZ();
        ThreadLocalRandom r = ThreadLocalRandom.current();

        for (int i = 0; i < attempts; i++) {
            int x = px + r.nextInt(-radius, radius + 1);
            int z = pz + r.nextInt(-radius, radius + 1);
            int y = py - r.nextInt(minD, maxD + 1);

            if (y - 1 < w.getMinHeight() + 5) continue;   // stay off bedrock
            if (y + 2 >= w.getMaxHeight()) continue;
            if (!isBuried(w, x, y, z)) continue;

            build(w, x, y, z);
            stashes.add(new Stash(w.getName(), x, y, z));
            saveStashes();
            p.sendMessage(MM.deserialize(msg("prefix") + msg("placed"),
                    Placeholder.unparsed("x", String.valueOf(x)),
                    Placeholder.unparsed("y", String.valueOf(y)),
                    Placeholder.unparsed("z", String.valueOf(z)),
                    Placeholder.unparsed("depth", String.valueOf(py - y))));
            return;
        }
        say(p, "no-spot");
    }

    /**
     * The 3x4x3 box around the stash (spawner + cake in the middle column) must be
     * completely solid, full blocks - so it is buried, not touching a cave, water or air.
     */
    private boolean isBuried(World w, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Material m = w.getBlockAt(x + dx, y + dy, z + dz).getType();
                    if (!m.isSolid() || !m.isOccluding() || m == Material.BEDROCK) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void build(World w, int x, int y, int z) {
        Block spawner = w.getBlockAt(x, y, z);
        spawner.setType(Material.SPAWNER, false);
        if (spawner.getState() instanceof CreatureSpawner cs) {
            cs.setSpawnedType(spawnerType());
            cs.update(true, false);
        }

        Block cake = w.getBlockAt(x, y + 1, z);
        cake.setType(Material.CANDLE_CAKE, false);
        if (cake.getBlockData() instanceof Lightable l) {
            l.setLit(getConfig().getBoolean("candle-lit", true));
            cake.setBlockData(l, false);
        }
    }

    private EntityType spawnerType() {
        try {
            return EntityType.valueOf(getConfig().getString("spawner-type", "ZOMBIE").toUpperCase());
        } catch (IllegalArgumentException e) {
            return EntityType.ZOMBIE;
        }
    }

    private void removeNearest(Player p) {
        Stash best = null;
        double bestDist = 10 * 10;
        for (Stash s : stashes) {
            if (!s.world().equals(p.getWorld().getName())) continue;
            double d = p.getLocation().distanceSquared(new org.bukkit.Location(p.getWorld(), s.x(), s.y(), s.z()));
            if (d <= bestDist) {
                bestDist = d;
                best = s;
            }
        }
        if (best == null) {
            say(p, "none-near");
            return;
        }
        World w = p.getWorld();
        Material fill = best.y() < 0 ? Material.DEEPSLATE : Material.STONE;
        w.getBlockAt(best.x(), best.y(), best.z()).setType(fill, false);
        w.getBlockAt(best.x(), best.y() + 1, best.z()).setType(fill, false);
        stashes.remove(best);
        saveStashes();
        p.sendMessage(MM.deserialize(msg("prefix") + msg("removed"),
                Placeholder.unparsed("x", String.valueOf(best.x())),
                Placeholder.unparsed("y", String.valueOf(best.y())),
                Placeholder.unparsed("z", String.valueOf(best.z()))));
    }

    // ------------------------------------------------------------ catch alerts

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Stash s = findAt(e.getBlock());
        if (s == null) return;

        Player p = e.getPlayer();
        stashes.remove(s);
        saveStashes();

        if (p.hasPermission("placestash.exempt")) return; // staff cleaning up

        TagResolver[] ph = {
                Placeholder.unparsed("player", p.getName()),
                Placeholder.unparsed("x", String.valueOf(s.x())),
                Placeholder.unparsed("y", String.valueOf(s.y())),
                Placeholder.unparsed("z", String.valueOf(s.z())),
                Placeholder.unparsed("world", s.world())
        };

        var alert = MM.deserialize(msg("prefix") + msg("alert"), ph);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("placestash.alert")) staff.sendMessage(alert);
        }
        Bukkit.getConsoleSender().sendMessage(alert);

        logCaught(p.getName(), s);

        for (String c : getConfig().getStringList("punish-commands")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{player}", p.getName()));
        }
    }

    private Stash findAt(Block b) {
        for (Stash s : stashes) {
            if (s.world().equals(b.getWorld().getName())
                    && s.x() == b.getX() && s.z() == b.getZ()
                    && (b.getY() == s.y() || b.getY() == s.y() + 1)) {
                return s;
            }
        }
        return null;
    }

    private void logCaught(String player, Stash s) {
        String line = LocalDateTime.now() + " | " + player + " broke stash at "
                + s.world() + " " + s.x() + " " + s.y() + " " + s.z() + System.lineSeparator();
        try {
            Files.writeString(new File(getDataFolder(), "caught.log").toPath(), line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            getLogger().warning("Could not write caught.log: " + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- storage

    private void loadStashes() {
        stashes.clear();
        var yml = YamlConfiguration.loadConfiguration(stashFile);
        for (String line : yml.getStringList("stashes")) {
            String[] p = line.split(";");
            if (p.length != 4) continue;
            try {
                stashes.add(new Stash(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void saveStashes() {
        var yml = new YamlConfiguration();
        yml.set("stashes", stashes.stream()
                .map(s -> s.world() + ";" + s.x() + ";" + s.y() + ";" + s.z()).toList());
        try {
            yml.save(stashFile);
        } catch (IOException e) {
            getLogger().warning("Could not save stashes.yml: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    private String msg(String key) {
        return getConfig().getString("messages." + key, key);
    }

    private void say(CommandSender to, String key) {
        to.sendMessage(MM.deserialize(msg("prefix") + msg(key)));
    }
}
