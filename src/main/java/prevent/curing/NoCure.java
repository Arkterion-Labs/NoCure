package prevent.curing;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NoCure extends JavaPlugin implements Listener {

    private final Map<UUID, UUID> weaknessAppliedTracker = new ConcurrentHashMap<>();
    private final Map<UUID, Long> notificationCooldowns = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("NoCure has been enabled!");
    }

    @Override
    public void onDisable() {
        weaknessAppliedTracker.clear();
        notificationCooldowns.clear();
        getLogger().info("NoCure has been disabled.");
    }

    /** Tracks which player most recently applied a splash Weakness potion. */
    @EventHandler(ignoreCancelled = true)
    public void onWeaknessPotion(PotionSplashEvent event) {
        if (event.getPotion().getEffects() == null || event.getPotion().getEffects().stream()
                .noneMatch(effect -> effect.getType().equals(PotionEffectType.WEAKNESS))) {
            return;
        }

        if (!(event.getEntity().getShooter() instanceof Player thrower)) {
            return;
        }

        event.getAffectedEntities().stream()
                .filter(ZombieVillager.class::isInstance)
                .forEach(entity -> weaknessAppliedTracker.put(entity.getUniqueId(), thrower.getUniqueId()));
    }

    /** Blocks the golden-apple interaction before a cure can begin. */
    @EventHandler(ignoreCancelled = true)
    public void onGoldenAppleUse(PlayerInteractAtEntityEvent event) {
        if (!(event.getRightClicked() instanceof ZombieVillager zombieVillager)) {
            return;
        }

        ItemStack usedItem = event.getPlayer().getInventory().getItem(event.getHand());
        if (usedItem == null || usedItem.getType() != Material.GOLDEN_APPLE) {
            return;
        }

        // Always block the cure attempt, regardless of notification cooldown.
        event.setCancelled(true);

        UUID trackedPlayerId = weaknessAppliedTracker.remove(zombieVillager.getUniqueId());
        Player curingPlayer = trackedPlayerId != null ? Bukkit.getPlayer(trackedPlayerId) : event.getPlayer();

        UUID playerId = curingPlayer != null
                ? curingPlayer.getUniqueId()
                : event.getPlayer().getUniqueId();

        String playerName = curingPlayer != null
                ? curingPlayer.getName()
                : event.getPlayer().getName();

        notifyAttempt(playerId, playerName, zombieVillager.getLocation());
    }

    /**
     * Final safety net: Paper exposes CURED specifically for zombie-villager cures.
     * If another plugin or a future interaction path starts conversion anyway,
     * the actual Zombie Villager -> Villager transformation is still cancelled.
     */
    @EventHandler(ignoreCancelled = true)
    public void onZombieVillagerCure(EntityTransformEvent event) {
        if (event.getTransformReason() == EntityTransformEvent.TransformReason.CURED
                && event.getEntityType() == EntityType.ZOMBIE_VILLAGER
                && event.getTransformedEntity().getType() == EntityType.VILLAGER) {
            event.setCancelled(true);
            weaknessAppliedTracker.remove(event.getEntity().getUniqueId());
        }
    }

    private void notifyAttempt(UUID playerId, String playerName, Location location) {
        long cooldownSeconds = Math.max(
                0L,
                getConfig().getLong("notifications.cooldown-seconds", 5L)
        );

        long now = System.currentTimeMillis();
        long cooldownMillis = cooldownSeconds * 1000L;

        Long lastNotification = notificationCooldowns.get(playerId);

        if (lastNotification != null && now - lastNotification < cooldownMillis) {
            return;
        }

        notificationCooldowns.put(playerId, now);

        String coordinates = "X:" + location.getBlockX()
                + " Y:" + location.getBlockY()
                + " Z:" + location.getBlockZ();

        String message = playerName
                + " tried to cure a zombie villager at "
                + coordinates;

        if (getConfig().getBoolean("notifications.console", true)) {
            getLogger().warning(message);
        }

        if (getConfig().getBoolean("notifications.operators", false)) {
            String inGameMessage =
                    ChatColor.RED + "[NoCure] "
                            + ChatColor.YELLOW + message;

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.isOp()) {
                    player.sendMessage(inGameMessage);
                }
            }
        }

        if (getConfig().getBoolean("notifications.discordsrv", false)
                && Bukkit.getPluginManager().isPluginEnabled("DiscordSRV")) {

            String channelName = getConfig().getString(
                    "notifications.discordsrv-channel",
                    "staff-chat"
            );

            TextChannel textChannel =
                    DiscordSRV.getPlugin()
                            .getDestinationTextChannelForGameChannelName(channelName);

            if (textChannel != null) {
                textChannel.sendMessage("**[NoCure]** " + message).queue();
            } else {
                getLogger().warning(
                        "Could not find DiscordSRV channel: " + channelName
                );
            }
        }
    }
}