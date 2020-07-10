package com.ruinscraft.kotl;

import com.sk89q.worldedit.bukkit.BukkitWorld;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.util.Vector;

import java.util.*;

public class KOTL extends JavaPlugin implements Listener, CommandExecutor {

    private static WorldGuard worldGuard;

    private Scoreboard scoreboard;
    private Objective objective;

    private String kotlRegion;
    private String kingRegion;

    private Location teleportSpot;

    private String currentKingUsername;

    private Map<UUID, Long> lastTimeAtKOTL = new HashMap<>();

    public void onEnable() {
        saveDefaultConfig();

        this.kotlRegion = this.getConfig().getString("kotlRegion");
        this.kingRegion = this.getConfig().getString("kingRegion");

        this.teleportSpot = new Location(Bukkit.getWorld(this.getConfig().getString("kotlTeleport.world")),
                this.getConfig().getDouble("kotlTeleport.x"), this.getConfig().getDouble("kotlTeleport.y"),
                this.getConfig().getDouble("kotlTeleport.z"),
                Float.parseFloat(this.getConfig().getString("kotlTeleport.yaw")),
                Float.parseFloat(this.getConfig().getString("kotlTeleport.pitch")));

        try {
            worldGuard = WorldGuard.getInstance();
        } catch (NullPointerException e) {
            getLogger().warning("WorldGuard required");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // create scoreboard/objective for kotl rankings
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        this.scoreboard = manager.getNewScoreboard();
        scoreboard.registerNewObjective("kotl", "dummy",
                ChatColor.GREEN + "" + ChatColor.BOLD + "King of the Ladder");
        objective = scoreboard.getObjective("kotl");
        objective.setRenderType(RenderType.INTEGER);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        // add events/cmd
        getServer().getPluginManager().registerEvents(this, this);
        this.getCommand("kotl").setExecutor(this);

        // task runs 10 times a second to check king and add point to current king
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            List<Player> kings = new ArrayList<>();
            for (Player player : this.getServer().getOnlinePlayers()) {
                if (this.isInKingRegion(player) && player.getGameMode() != GameMode.CREATIVE &&
                        player.getGameMode() != GameMode.SPECTATOR) {
                    kings.add(player);
                }
            }

            if (kings.size() == 1) {
                Player player = kings.get(0);
                String name = player.getName();

                if (this.currentKingUsername == null || !name.equals(this.currentKingUsername)) {
                    Bukkit.broadcastMessage(ChatColor.GREEN + name + " is the " +
                            ChatColor.YELLOW + "King of the Ladder" + ChatColor.GREEN + "! /kotl");
                    this.currentKingUsername = name;
                }

                int oldScore = objective.getScore(name).getScore();
                objective.getScore(name).setScore(oldScore + 1);
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.GREEN + "You are the king!"));
            } else if (kings.size() > 1) {
                for (Player player : kings) {
                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.RED + "No one is the king!"));
                }
            }
        }, 20, 2);
    }

    public void onDisable() {
        scoreboard = null;
        objective = null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (isEnteringKOTLRegion(event.getFrom(), event.getTo())) {
            // send people backward if they are flying while they are entering
            if (player.isFlying()) {
                Vector velocity = player.getVelocity();
                velocity.setX(velocity.getX() * -1);
                velocity.setZ(velocity.getZ() * -1);
                velocity.multiply(2);
                player.setVelocity(velocity);
            }

            // disable flight for anyone who doesnt have the ability to change gamemode
            if (player.getGameMode() != GameMode.CREATIVE &&
                    player.getGameMode() != GameMode.SPECTATOR) {
                player.setFlying(false);
                player.setAllowFlight(false);
            }

            lastTimeAtKOTL.put(player.getUniqueId(), System.currentTimeMillis());

            if (player.isInsideVehicle()) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "Can't enter KOTL while riding something!");
                return;
            }

            // if they are entering, set their scoreboard as the KOTL scoreboard
            player.setScoreboard(scoreboard);
            Score score = objective.getScore(player.getName());
            if (!(score.isScoreSet())) {
                objective.getScore(player.getName()).setScore(0);
            }
        } else if (isLeavingKOTLRegion(event.getFrom(), event.getTo())) {
            // enable flight again if they are VIP and leaving the area
            if (player.hasPermission("group.vip1")) {
                player.setAllowFlight(true);
            }

            // removes the kotl scoreboard
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());

            // add last time user was at kotl
            lastTimeAtKOTL.put(player.getUniqueId(), System.currentTimeMillis());

            String name = player.getName();
            Bukkit.getScheduler().runTaskLater(this, () -> {
                long lastTime = lastTimeAtKOTL.get(player.getUniqueId());
                // if player is offline or if (player isn't in region and hasn't been in region for 85+ secs), remove
                // the player's score
                if ((Bukkit.getPlayer(name) == null) ||
                        System.currentTimeMillis() - lastTime >= 85000 && !isInKOTLRegion(player)) {
                    scoreboard.resetScores(player.getName());
                    lastTimeAtKOTL.remove(player.getUniqueId());
                }
            }, 20 * 90);
        }

        // disable flight if they r in KOTL and they arent OP or whatever
        if (isInKOTLRegion(player) && player.getGameMode() != GameMode.CREATIVE &&
                player.getGameMode() != GameMode.SPECTATOR) {
            player.setAllowFlight(false);
        }
        // make sure to set in SimplePets config the wg regions for kotl/king! if it works hhahaha
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getName();

        // put the last time they were in KOTL if they were in KOTL
        Location location = player.getLocation();
        if (isInKOTLRegion(location)) {
            lastTimeAtKOTL.put(uuid, System.currentTimeMillis());
        }

        // remove player from scores if they r gone
        Bukkit.getScheduler().runTaskLater(this, () -> {
            long lastTime = lastTimeAtKOTL.get(uuid);
            if (lastTime == -1L || player == null || Bukkit.getPlayer(name) == null ||
                    (System.currentTimeMillis() - lastTime >= 85000 && !isInKOTLRegion(player))) {
                scoreboard.resetScores(name);
                lastTimeAtKOTL.remove(uuid);
            }
        }, 20 * 90);
    }

    // no dying
    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        event.setDamage(0);
    }

    public boolean isEnteringKOTLRegion(Location from, Location to) {
        if (isWithinRegion(to, this.kotlRegion) && !isWithinRegion(from, this.kotlRegion)) return true;
        return false;
    }

    public boolean isLeavingKOTLRegion(Location from, Location to) {
        if (isWithinRegion(from, this.kotlRegion) && !isWithinRegion(to, this.kotlRegion)) return true;
        return false;
    }

    public boolean isInKOTLRegion(Player player) {
        return isWithinRegion(player, this.kotlRegion);
    }

    public boolean isInKOTLRegion(Location location) {
        return isWithinRegion(location, this.kotlRegion);
    }

    public boolean isInKingRegion(Player player) {
        return isWithinRegion(player, this.kingRegion);
    }

    public boolean isWithinRegion(Player player, String region) {
        return isWithinRegion(player.getLocation(), region);
    }

    // method using worldguard api to check if a location is within specified region
    public boolean isWithinRegion(Location loc, String region) {
        BukkitWorld world = new BukkitWorld(loc.getWorld());
        RegionManager regionManager = worldGuard.getPlatform().getRegionContainer().get(world);
        ApplicableRegionSet set = regionManager.getApplicableRegions(BlockVector3.at(loc.getX(), loc.getY(), loc.getZ()));

        for (ProtectedRegion each : set) {
            if (each.getId().equalsIgnoreCase(region)) {
                return true;
            }
        }

        return false;
    }

    // /kotl command
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return false;
        Player player = (Player) sender;
        Location location = this.teleportSpot.clone();
        player.teleport(location);
        player.sendMessage(ChatColor.GREEN + "Teleported to King of the Ladder!");
        return true;
    }

}
