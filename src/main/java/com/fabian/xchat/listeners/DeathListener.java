package com.fabian.xchat.listeners;

import com.fabian.xchat.XChat;
import com.fabian.xchat.utils.ColorUtils;
import com.fabian.xchat.utils.DebugLogger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class DeathListener implements Listener {

    private final XChat plugin;

    // Guardamos el último atacante del jugador para usarlo en la muerte
    public DeathListener(XChat plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        DebugLogger.debug("DeathListener", "Processing death for " + player.getName());
        EntityDamageByEntityEvent lastDamage = (player.getLastDamageCause() instanceof EntityDamageByEntityEvent)
                ? (EntityDamageByEntityEvent) player.getLastDamageCause()
                : null;

        String message = resolveDeathMessage(player, lastDamage);

        if (message != null && !message.isEmpty()) {
            event.setDeathMessage(null); // Cancel default death message
            String legacy = ColorUtils.toLegacyString(ColorUtils.format(player, message));
            for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(legacy);
            Bukkit.getConsoleSender().sendMessage(legacy);
        }
    }

    private String resolveDeathMessage(Player player, EntityDamageByEntityEvent lastDamage) {
        org.bukkit.event.entity.EntityDamageEvent.DamageCause cause =
                player.getLastDamageCause() != null ? player.getLastDamageCause().getCause() : null;

        if (cause == null) return getMsg("generic.message", player, null, null);

        switch (cause) {
            case ENTITY_ATTACK:
            case ENTITY_SWEEP_ATTACK:
                return handleEntityKill(player, lastDamage);

            case PROJECTILE:
                return handleProjectile(player, lastDamage);

            case FALL:
                return getMsg("fall.message", player, null, null);

            case DROWNING:
                return getMsg("drown.message", player, null, null);

            case FIRE:
                return getMsg("fire.on-fire", player, null, null);

            case FIRE_TICK:
                return getMsg("fire.burned", player, null, null);

            case LAVA:
                return getMsg("lava.message", player, null, null);

            case VOID:
                return getMsg("void.message", player, null, null);

            case SUFFOCATION:
                return getMsg("suffocation.message", player, null, null);

            case STARVATION:
                return getMsg("starvation.message", player, null, null);

            case BLOCK_EXPLOSION:
            case ENTITY_EXPLOSION:
                return handleExplosion(player, lastDamage);

            case CONTACT: // Cactus, berries
                return getMsg("cactus.message", player, null, null);

            case LIGHTNING:
                return getMsg("lightning.message", player, null, null);

            case MAGIC:
            case POISON:
            case WITHER:
                return getMsg("magic.message", player, null, null);

            default:
                return getMsg("generic.message", player, null, null);
        }
    }

    @SuppressWarnings("deprecation")
    private String handleEntityKill(Player dead, EntityDamageByEntityEvent event) {
        if (event == null) return getMsg("generic.message", dead, null, null);

        Entity damager = event.getDamager();
        String killerName = damager.getName();
        String weaponName = null;

        if (damager instanceof Player) {
            Player killer = (Player) damager;
            killerName = killer.getName();
            ItemStack hand = killer.getInventory().getItemInMainHand();
            if (hand.getType() != org.bukkit.Material.AIR) {
                ItemMeta meta = hand.getItemMeta();
                weaponName = (meta != null && meta.hasDisplayName()) ? meta.getDisplayName()
                        : capitalize(hand.getType().name().replace("_", " ").toLowerCase());
            }
            String key = weaponName != null ? "pvp.with-weapon" : "pvp.without-weapon";
            return getMsg(key, dead, killerName, weaponName);
        } else {
            // Mob kill - try to extract weapon for armed mobs (skeletons, pillagers, etc.)
            if (damager instanceof org.bukkit.entity.LivingEntity) {
                ItemStack mobHand = ((org.bukkit.entity.LivingEntity) damager).getEquipment().getItemInMainHand();
                if (mobHand.getType() != org.bukkit.Material.AIR) {
                    ItemMeta meta = mobHand.getItemMeta();
                    weaponName = (meta != null && meta.hasDisplayName()) ? meta.getDisplayName()
                            : capitalize(mobHand.getType().name().replace("_", " ").toLowerCase());
                }
            }
            String key = weaponName != null ? "mob.with-weapon" : "mob.without-weapon";
            return getMsg(key, dead, killerName, weaponName);
        }
    }

    private String handleProjectile(Player dead, EntityDamageByEntityEvent event) {
        if (event == null) return getMsg("projectile.generic", dead, null, null);

        Entity damager = event.getDamager();
        if (damager instanceof Projectile) {
            org.bukkit.projectiles.ProjectileSource shooter = ((Projectile) damager).getShooter();
            if (shooter instanceof Player) {
                String killerName = ((Player) shooter).getName();
                return getMsg("projectile.by-player", dead, killerName, null);
            }
        }
        return getMsg("projectile.generic", dead, null, null);
    }

    private String handleExplosion(Player dead, EntityDamageByEntityEvent event) {
        if (event != null && event.getDamager() instanceof Player) {
            String killerName = event.getDamager().getName();
            return getMsg("explosion.by-player", dead, killerName, null);
        }
        return getMsg("explosion.generic", dead, null, null);
    }

    private String getMsg(String key, Player player, String killer, String weapon) {
        if (!plugin.getDeathsConfig().getBoolean(key.split("\\.")[0] + ".enabled", true)) return null;

        String raw = plugin.getDeathsConfig().getString(key, "");
        if (raw.isEmpty()) return null;

        return raw.replace("%player%", player.getName())
                  .replace("%killer%", killer != null ? killer : "?")
                  .replace("%weapon%", weapon != null ? weapon : "?")
                  .replace("%world%", player.getWorld().getName());
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        String[] words = s.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(" ");
        return sb.toString().trim();
    }
}
