package me.fokend.greatblacksmith;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * Обработчик баланса кастомных зелий Лавки Житака и 4 уникальных кастомных предметов:
 * 1. Палка-Булава Великого Кузнеца (mace_stick)
 * 2. Крюк-Кошка Шлакосборщика (slag_grapple)
 * 3. Эссенция Жидкого Шлака (molten_slag_potion)
 * 4. Печать Раскалённой Наковальни (anvil_totem)
 */
public final class BlacksmithCombatAndPotionListener implements Listener {

    private final FokendGreatBlacksmith plugin;
    private final NamespacedKey specialItemKey;
    private final NamespacedKey customPotionCooldownKey;

    // Cooldowns & status timers (UUID -> expiration timestamp in millis)
    private final Map<UUID, Long> potionCooldowns = new HashMap<>();
    private final Map<UUID, Long> maceWindCooldowns = new HashMap<>();
    private final Map<UUID, Long> maceSlamCooldowns = new HashMap<>();
    private final Map<UUID, Long> grappleCooldowns = new HashMap<>();
    private final Map<UUID, Long> grappleFallImmunity = new HashMap<>();
    private final Map<UUID, Long> groundedUntil = new HashMap<>();
    private final Map<UUID, Long> slagBurnUntil = new HashMap<>();
    private final Map<UUID, Long> anvilTotemCooldowns = new HashMap<>();
    private final Map<UUID, Long> warhammerSlamCooldowns = new HashMap<>();
    private final Map<UUID, Long> vulcanSteamCooldowns = new HashMap<>();
    private final Map<UUID, Long> titanDraughtCooldowns = new HashMap<>();

    public BlacksmithCombatAndPotionListener(FokendGreatBlacksmith plugin) {
        this.plugin = plugin;
        this.specialItemKey = new NamespacedKey(plugin, "blacksmith_special");
        this.customPotionCooldownKey = new NamespacedKey(plugin, "potion_cd_seconds");
    }

    public void clearAll() {
        potionCooldowns.clear();
        maceWindCooldowns.clear();
        maceSlamCooldowns.clear();
        grappleCooldowns.clear();
        grappleFallImmunity.clear();
        groundedUntil.clear();
        slagBurnUntil.clear();
        anvilTotemCooldowns.clear();
        warhammerSlamCooldowns.clear();
        vulcanSteamCooldowns.clear();
        titanDraughtCooldowns.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        potionCooldowns.remove(id);
        maceWindCooldowns.remove(id);
        maceSlamCooldowns.remove(id);
        grappleCooldowns.remove(id);
        grappleFallImmunity.remove(id);
        groundedUntil.remove(id);
        slagBurnUntil.remove(id);
        anvilTotemCooldowns.remove(id);
        warhammerSlamCooldowns.remove(id);
        vulcanSteamCooldowns.remove(id);
        titanDraughtCooldowns.remove(id);
    }

    private String getSpecialId(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(specialItemKey, PersistentDataType.STRING);
    }

    private int getPotionCooldownSeconds(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return 0;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return 0;
        Integer sec = meta.getPersistentDataContainer().get(customPotionCooldownKey, PersistentDataType.INTEGER);
        return sec != null ? sec : 0;
    }

    // =========================================================================
    // 1. CUSTOM POTIONS BALANCE, COOLDOWNS & MOLTEN SLAG ANTI-HEAL
    // =========================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCustomPotionDrink(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        // Check if player is afflicted by Molten Slag Burn while eating Golden/Enchanted Golden Apples
        if (item.getType() == Material.GOLDEN_APPLE || item.getType() == Material.ENCHANTED_GOLDEN_APPLE) {
            long burnEnd = slagBurnUntil.getOrDefault(player.getUniqueId(), 0L);
            if (System.currentTimeMillis() < burnEnd) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!player.isOnline()) return;
                    PotionEffect abs = player.getPotionEffect(PotionEffectType.ABSORPTION);
                    if (abs != null && abs.getAmplifier() > 0) {
                        int reducedAmp = Math.max(0, (abs.getAmplifier() + 1) / 2 - 1);
                        player.removePotionEffect(PotionEffectType.ABSORPTION);
                        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, abs.getDuration(), reducedAmp));
                        player.sendActionBar(Component.text("🔥 Шлаковый ожог уполовинил щит Поглощения!", NamedTextColor.GOLD));
                    }
                }, 1L);
            }
            return;
        }

        if ("titan_draught".equals(getSpecialId(item))) {
            long now = System.currentTimeMillis();
            long readyAt = titanDraughtCooldowns.getOrDefault(player.getUniqueId(), 0L);
            if (now < readyAt) {
                long rem = (readyAt - now + 999L) / 1000L;
                event.setCancelled(true);
                player.sendActionBar(Component.text("⏳ Эликсир Титана перезаряжается: " + rem + " сек.", NamedTextColor.RED));
                return;
            }
            titanDraughtCooldowns.put(player.getUniqueId(), now + 45_000L);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 20 * 25, 2)); // 6 golden hearts
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 20 * 25, 1)); // Resistance II
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * 25, 0));   // Slowness I
                player.playSound(player.getLocation(), Sound.ENTITY_IRON_GOLEM_REPAIR, 1.2F, 0.8F);
                player.getWorld().spawnParticle(Particle.LAVA, player.getLocation().add(0, 1, 0), 15, 0.4, 0.6, 0.4, 0.05);
                player.sendMessage(Component.text("✦ Вы выпили Эликсир Титана! (+6 сердец и Сопротивление II на 25 сек.)", NamedTextColor.LIGHT_PURPLE));
            });
            return;
        }

        int cdSeconds = getPotionCooldownSeconds(item);
        if (cdSeconds <= 0) return;

        long now = System.currentTimeMillis();
        long readyAt = potionCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now < readyAt) {
            long rem = (readyAt - now + 999L) / 1000L;
            event.setCancelled(true);
            player.sendMessage(Component.text("⏳ Алхимический перегрев! Подождите ещё " + rem + " сек. перед следующим эликсиром Житака.", NamedTextColor.RED));
            return;
        }

        potionCooldowns.put(player.getUniqueId(), now + cdSeconds * 1000L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSplashPotionInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (item == null || (item.getType() != Material.SPLASH_POTION && item.getType() != Material.LINGERING_POTION)) return;

        int cdSeconds = getPotionCooldownSeconds(item);
        if (cdSeconds <= 0) return;

        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        long readyAt = potionCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now < readyAt) {
            long rem = (readyAt - now + 999L) / 1000L;
            event.setCancelled(true);
            player.sendActionBar(Component.text("⏳ Перезарядка боевого эликсира: " + rem + " сек.", NamedTextColor.RED));
            return;
        }

        potionCooldowns.put(player.getUniqueId(), now + cdSeconds * 1000L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        ThrownPotion thrown = event.getPotion();
        ItemStack item = thrown.getItem();
        String special = getSpecialId(item);
        int cdSeconds = getPotionCooldownSeconds(item);
        if (special == null && cdSeconds <= 0) return;

        Location splashLoc = thrown.getLocation();
        long now = System.currentTimeMillis();

        // Linear PvP distance scaling for Blacksmith custom splash potions
        for (LivingEntity entity : event.getAffectedEntities()) {
            double dist = entity.getLocation().distance(splashLoc);
            double factor = dist <= 2.0D ? 1.0D : Math.max(0.5D, 1.0D - 0.15D * (dist - 1.5D));
            event.setIntensity(entity, factor);

            if ("molten_slag_potion".equals(special) && entity instanceof Player target) {
                slagBurnUntil.put(target.getUniqueId(), now + 10_000L);
                target.sendMessage(Component.text("🔥 Вы поражены Эссенцией Жидкого Шлака! Исцеление и Поглощение снижены на 50% (10 сек.)", NamedTextColor.RED));
                target.getWorld().spawnParticle(Particle.LAVA, target.getLocation().add(0, 1, 0), 18, 0.35, 0.5, 0.35, 0.05);
                target.playSound(target.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 1.0F, 0.8F);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegainHealth(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        long burnEnd = slagBurnUntil.getOrDefault(player.getUniqueId(), 0L);
        if (System.currentTimeMillis() < burnEnd) {
            event.setAmount(event.getAmount() * 0.5D);
            player.getWorld().spawnParticle(Particle.FALLING_LAVA, player.getLocation().add(0, 1.1, 0), 3, 0.25, 0.3, 0.25, 0.01);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrewCustomPotion(BrewEvent event) {
        for (int i = 0; i < 3; i++) {
            ItemStack bottle = event.getContents().getItem(i);
            if (getPotionCooldownSeconds(bottle) > 0 || getSpecialId(bottle) != null) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // =========================================================================
    // 2. MACE-STICK ("mace_stick"): SHIELD BREAKER, MINI-SLAM & WIND IMPULSE
    // =========================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGroundedProjectile(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player)) return;
        if (!(event.getEntity() instanceof EnderPearl) && !(event.getEntity() instanceof WindCharge)) return;
        long groundEnd = groundedUntil.getOrDefault(player.getUniqueId(), 0L);
        if (System.currentTimeMillis() < groundEnd) {
            long rem = (groundEnd - System.currentTimeMillis() + 999L) / 1000L;
            event.setCancelled(true);
            player.sendActionBar(Component.text("⛓ Вы заземлены Крюком Шлакосборщика! (" + rem + " сек.)", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMaceStickRightClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack held = event.getItem();
        String special = getSpecialId(held);

        // Правый клик по интерактивному блоку (сундук, дверь...) не должен стрелять ветром
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && event.getClickedBlock().getType().isInteractable() && !player.isSneaking()) {
            return;
        }

        if (!"mace_stick".equals(special)) return;

        long now = System.currentTimeMillis();
        long readyAt = maceWindCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now < readyAt) {
            long rem = (readyAt - now + 999L) / 1000L;
            player.sendActionBar(Component.text("⏳ Вихревой рывок перезаряжается: " + rem + " сек.", NamedTextColor.RED));
            return;
        }

        maceWindCooldowns.put(player.getUniqueId(), now + 8_000L);

        WindCharge charge = player.launchProjectile(WindCharge.class);
        charge.setVelocity(player.getLocation().getDirection().normalize().multiply(1.8D));
        player.playSound(player.getLocation(), Sound.ENTITY_BREEZE_SHOOT, 1.0F, 1.1F);
        player.getWorld().spawnParticle(Particle.GUST, player.getEyeLocation(), 2, 0.1, 0.1, 0.1, 0.02);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCombatAttackAndDefense(EntityDamageByEntityEvent event) {
        // 1. Vulcan Shield reflection & steam burst when blocking
        if (event.getEntity() instanceof Player victim && victim.isBlocking()) {
            ItemStack activeShield = null;
            if ("vulcan_shield".equals(getSpecialId(victim.getInventory().getItemInOffHand()))) {
                activeShield = victim.getInventory().getItemInOffHand();
            } else if ("vulcan_shield".equals(getSpecialId(victim.getInventory().getItemInMainHand()))) {
                activeShield = victim.getInventory().getItemInMainHand();
            }
            if (activeShield != null && event.getDamager() instanceof LivingEntity damager) {
                double originalDamage = event.getDamage();
                double reflectDamage = Math.max(3.0D, originalDamage * 0.40D);
                damager.damage(reflectDamage, victim);
                damager.setFireTicks(80);
                damager.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0));
                Location hitLoc = victim.getLocation().add(0, 1.0, 0);
                hitLoc.getWorld().playSound(hitLoc, Sound.BLOCK_LAVA_EXTINGUISH, 1.3F, 1.0F);
                hitLoc.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, hitLoc, 20, 0.3, 0.3, 0.3, 0.05);
                hitLoc.getWorld().spawnParticle(Particle.FLAME, hitLoc, 12, 0.3, 0.3, 0.3, 0.08);
                victim.sendActionBar(Component.text("🛡 Бастион Гефеста отразил урон паром и огнем!", NamedTextColor.GOLD));
            }
        }

        // 2. Weapon attacks
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof LivingEntity victimEntity)) {
            return;
        }
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        String special = getSpecialId(weapon);

        if ("forge_warhammer".equals(special)) {
            victimEntity.setFireTicks(100);
            victimEntity.getWorld().spawnParticle(Particle.LAVA, victimEntity.getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0.05);
            victimEntity.getWorld().playSound(victimEntity.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.8F, 1.5F);
            return;
        }

        if (!"mace_stick".equals(special)) return;

        long now = System.currentTimeMillis();
        float fallDist = attacker.getFallDistance();
        boolean isJumpingCrit = fallDist > 0.0F && !attacker.isOnGround() && !attacker.isInWater();

        // Shield Breaker on jump hit
        if (isJumpingCrit && victimEntity instanceof Player targetPlayer && targetPlayer.isBlocking()) {
            targetPlayer.setCooldown(Material.SHIELD, 100); // 5 seconds shield disable
            targetPlayer.clearActiveItem();
            double bleedThrough = Math.max(2.0D, event.getDamage() * 0.30D);
            targetPlayer.damage(bleedThrough);
            targetPlayer.getWorld().playSound(targetPlayer.getLocation(), Sound.ITEM_SHIELD_BREAK, 1.2F, 0.8F);
            targetPlayer.getWorld().playSound(targetPlayer.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.7F, 1.2F);
            attacker.sendActionBar(Component.text("🔨 Вы раскололи щит противника на 5 секунд!", NamedTextColor.GOLD));
            targetPlayer.sendActionBar(Component.text("⚠ Ваш щит оглушён Палкой-Булавой на 5 секунд!", NamedTextColor.RED));
        }

        // Mini-slam bonus when falling 4+ blocks (capped at +10 damage, cancels fall damage)
        if (fallDist >= 4.0F) {
            long slamReady = maceSlamCooldowns.getOrDefault(attacker.getUniqueId(), 0L);
            if (now >= slamReady) {
                maceSlamCooldowns.put(attacker.getUniqueId(), now + 6_000L);
                double bonusDamage = Math.min(10.0D, (fallDist - 3.0D) * 1.5D);
                event.setDamage(event.getDamage() + bonusDamage);
                attacker.setFallDistance(0.0F);

                Location hitLoc = victimEntity.getLocation();
                hitLoc.getWorld().spawnParticle(Particle.EXPLOSION, hitLoc.clone().add(0, 0.5, 0), 2, 0.2, 0.2, 0.2, 0.02);
                hitLoc.getWorld().playSound(hitLoc, Sound.ITEM_MACE_SMASH_GROUND, 1.2F, 1.0F);
                attacker.sendActionBar(Component.text(
                        String.format("⚒ Инерционный удар Булавы: +%.1f ед. урона!", bonusDamage),
                        NamedTextColor.GOLD));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onWarhammerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (!"forge_warhammer".equals(getSpecialId(item))) return;

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && event.getClickedBlock().getType().isInteractable() && !player.isSneaking()) {
            return;
        }

        long now = System.currentTimeMillis();
        long readyAt = warhammerSlamCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now < readyAt) {
            long rem = (readyAt - now + 999L) / 1000L;
            player.sendActionBar(Component.text("⏳ Молот Вулкана перезаряжается: " + rem + " сек.", NamedTextColor.RED));
            return;
        }

        warhammerSlamCooldowns.put(player.getUniqueId(), now + 12_000L);
        Location center = player.getLocation();
        center.getWorld().playSound(center, Sound.ITEM_MACE_SMASH_GROUND, 1.4F, 0.8F);
        center.getWorld().playSound(center, Sound.BLOCK_ANVIL_LAND, 1.0F, 0.7F);
        center.getWorld().spawnParticle(Particle.LAVA, center.clone().add(0, 0.5, 0), 30, 1.5, 0.3, 1.5, 0.1);
        center.getWorld().spawnParticle(Particle.EXPLOSION, center.clone().add(0, 0.5, 0), 4, 1.0, 0.2, 1.0, 0.05);

        for (Entity nearby : center.getWorld().getNearbyEntities(center, 6.0D, 3.5D, 6.0D)) {
            if (nearby instanceof LivingEntity target && !target.equals(player) && !(target instanceof ArmorStand)) {
                if (target instanceof Player tp && (tp.getGameMode() == GameMode.SPECTATOR || tp.getGameMode() == GameMode.CREATIVE)) {
                    continue;
                }
                if (target.getPersistentDataContainer().has(new NamespacedKey(plugin, "blacksmith_seller"), PersistentDataType.BYTE)) {
                    continue;
                }
                Vector push = target.getLocation().toVector().subtract(center.toVector());
                if (push.lengthSquared() < 0.01D) {
                    push = new Vector(0.2D, 0.75D, 0.2D);
                } else {
                    push.normalize().multiply(0.8D).setY(0.72D);
                }
                target.setVelocity(push);
                target.damage(12.0D, player);
                target.setFireTicks(80);
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 2)); // Slowness III
                target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 80, 1)); // Weakness II
            }
        }
        player.sendActionBar(Component.text("⚒ Земля содрогнулась от сокрушительного удара Молота!", NamedTextColor.GOLD));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrossbowShoot(EntityShootBowEvent event) {
        ItemStack bow = event.getBow();
        if (bow == null || !"slag_crossbow".equals(getSpecialId(bow))) return;
        Entity proj = event.getProjectile();
        proj.getPersistentDataContainer().set(new NamespacedKey(plugin, "slag_arrow"), PersistentDataType.BYTE, (byte) 1);
        proj.setVisualFire(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSlagArrowHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Projectile proj)) return;
        if (!proj.getPersistentDataContainer().has(new NamespacedKey(plugin, "slag_arrow"), PersistentDataType.BYTE)) return;
        Location loc = proj.getLocation();
        loc.getWorld().spawnParticle(Particle.LAVA, loc, 25, 0.5, 0.5, 0.5, 0.1);
        loc.getWorld().spawnParticle(Particle.EXPLOSION, loc, 3, 0.3, 0.3, 0.3, 0.05);
        loc.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.2F, 1.3F);
        Entity damager = proj.getShooter() instanceof Entity shooter ? shooter : null;
        for (Entity nearby : loc.getWorld().getNearbyEntities(loc, 4.0D, 3.0D, 4.0D)) {
            if (nearby instanceof LivingEntity target && !(target instanceof ArmorStand)) {
                if (damager != null && nearby.equals(damager)) continue;
                if (target instanceof Player p && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)) continue;
                if (target.getPersistentDataContainer().has(new NamespacedKey(plugin, "blacksmith_seller"), PersistentDataType.BYTE)) continue;
                target.damage(9.0D, damager);
                target.setFireTicks(100);
            }
        }
    }

    // =========================================================================
    // 3. SLAG GRAPPLE ("slag_grapple" - FISHING ROD)
    // =========================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSlagGrappleFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        boolean isGrapple = "slag_grapple".equals(getSpecialId(main)) || "slag_grapple".equals(getSpecialId(off));
        if (!isGrapple) return;

        PlayerFishEvent.State state = event.getState();
        if (state != PlayerFishEvent.State.CAUGHT_ENTITY
                && state != PlayerFishEvent.State.IN_GROUND
                && state != PlayerFishEvent.State.REEL_IN) {
            return;
        }

        long now = System.currentTimeMillis();
        // Крюк-кошка: кулдаун полностью снят по запросу пользователя!

        FishHook hook = event.getHook();

        // Mode A: Caught an enemy player/entity -> Pull them & apply 3s Grounded
        if (state == PlayerFishEvent.State.CAUGHT_ENTITY && event.getCaught() instanceof LivingEntity caught) {
            Vector pull = player.getLocation().toVector().subtract(caught.getLocation().toVector());
            if (pull.lengthSquared() > 0.01D) {
                Vector vel = pull.normalize().multiply(1.35D).setY(0.38D);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (caught.isValid()) caught.setVelocity(vel);
                });
            }
            if (caught instanceof Player targetPlayer) {
                groundedUntil.put(targetPlayer.getUniqueId(), now + 3_000L);
                targetPlayer.setCooldown(Material.ENDER_PEARL, 60);
                targetPlayer.setCooldown(Material.WIND_CHARGE, 60);
                targetPlayer.sendMessage(Component.text("⛓ Крюк Шлакосборщика притянул вас и заблокировал Жемчуг/Ветер на 3 сек.!", NamedTextColor.RED));
            }
            player.playSound(player.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1.2F, 0.8F);
            return;
        }

        // Mode B: Hooked into ground or solid block -> Dash toward hook
        boolean isBlockHook = (state == PlayerFishEvent.State.IN_GROUND)
                || (state == PlayerFishEvent.State.REEL_IN && (hook.isOnGround() || isNearSolidBlock(hook.getLocation())));

        if (isBlockHook && hook.getHookedEntity() == null && (!hook.isInWater() || state == PlayerFishEvent.State.IN_GROUND)) {
            Location hookLoc = hook.getLocation();
            Location playerLoc = player.getLocation();
            if (hookLoc.getWorld().equals(playerLoc.getWorld())) {
                double dx = hookLoc.getX() - playerLoc.getX();
                double dy = hookLoc.getY() - playerLoc.getY();
                double dz = hookLoc.getZ() - playerLoc.getZ();

                double distXZ = Math.sqrt(dx * dx + dz * dz);
                double dist = Math.sqrt(distXZ * distXZ + dy * dy);

                // Minimum distance of 1.2 blocks avoids zero-length jerks, max 32 blocks
                if (dist >= 1.2D && dist <= 32.0D) {
                    // Scaled horizontal speed: smoothly scales between 0.75 and 1.85 depending on distance
                    double horizSpeed = Math.min(1.85D, Math.max(0.75D, 0.55D + distXZ * 0.065D));
                    Vector horiz = (distXZ > 0.05D)
                            ? new Vector(dx, 0.0D, dz).normalize().multiply(horizSpeed)
                            : new Vector(0.0D, 0.0D, 0.0D);

                    // Dynamic vertical velocity:
                    // If target is above player, calculate required initial lift to overcome gravity and reach/clear the ledge
                    // If target is level or below, provide a small hop (0.42 = jump height) to avoid ground drag stopping player
                    double vy;
                    if (dy > 0.0D) {
                        vy = Math.min(1.75D, 0.38D + Math.sqrt(dy) * 0.35D + (dy * 0.03D));
                    } else {
                        vy = Math.max(0.20D, 0.42D + (dy * 0.03D));
                    }

                    Vector dash = new Vector(horiz.getX(), vy, horiz.getZ());
                    player.setVelocity(dash);
                    player.setFallDistance(0.0F);

                    // Grant landing fall protection for up to 5 seconds
                    grappleFallImmunity.put(player.getUniqueId(), now + 5_000L);

                    player.playSound(player.getLocation(), Sound.ENTITY_BREEZE_JUMP, 1.0F, 1.1F);
                    player.playSound(hookLoc, Sound.BLOCK_CHAIN_PLACE, 0.9F, 1.4F);
                    player.getWorld().spawnParticle(Particle.CRIT, player.getLocation().add(0, 1, 0), 16, 0.3, 0.3, 0.3, 0.1);
                    player.getWorld().spawnParticle(Particle.FIREWORK, hookLoc, 10, 0.2, 0.2, 0.2, 0.05);
                }
            }
        }
    }

    private boolean isNearSolidBlock(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        if (loc.getBlock().getType().isSolid()) return true;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    if (loc.clone().add(dx * 0.75D, dy * 0.75D, dz * 0.75D).getBlock().getType().isSolid()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGrappleFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(event.getEntity() instanceof Player player)) return;

        Long immuneUntil = grappleFallImmunity.get(player.getUniqueId());
        if (immuneUntil != null) {
            if (System.currentTimeMillis() <= immuneUntil) {
                event.setCancelled(true);
                player.setFallDistance(0.0F);
                grappleFallImmunity.remove(player.getUniqueId());
                player.playSound(player.getLocation(), Sound.ENTITY_BREEZE_LAND, 0.8F, 1.2F);
            } else {
                grappleFallImmunity.remove(player.getUniqueId());
            }
        }
    }

    // =========================================================================
    // 4. ANVIL TOTEM ("anvil_totem" - TOTEM OF UNDYING)
    // =========================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnvilTotemResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        EquipmentSlot hand = event.getHand();
        if (hand == null) return;

        ItemStack totem = player.getInventory().getItem(hand);
        if (!"anvil_totem".equals(getSpecialId(totem))) return;

        long now = System.currentTimeMillis();
        long readyAt = anvilTotemCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now < readyAt) {
            long rem = (readyAt - now + 999L) / 1000L;
            player.sendMessage(Component.text("⚠ Печать Раскалённой Наковальни ещё на перезарядке (" + rem + " сек.) — сработал только базовый эффект тотема!", NamedTextColor.YELLOW));
            return;
        }

        anvilTotemCooldowns.put(player.getUniqueId(), now + 90_000L);

        // Schedule Forge Blast & Armor Repair 1 tick after vanilla totem clears effects
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || player.isDead()) return;

            // 1. Repair worn armor by +15% max durability
            for (ItemStack armor : player.getInventory().getArmorContents()) {
                if (armor == null || armor.getType().isAir() || armor.getType().getMaxDurability() <= 0) continue;
                if (armor.getItemMeta() instanceof Damageable dmg) {
                    int maxDur = armor.getType().getMaxDurability();
                    int repairAmount = Math.max(1, (int) Math.round(maxDur * 0.15D));
                    dmg.setDamage(Math.max(0, dmg.getDamage() - repairAmount));
                    armor.setItemMeta((ItemMeta) dmg);
                }
            }

            // 2. Enhanced Blacksmith buffs
            player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 20 * 40, 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * 8, 2));
            player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 20 * 15, 2));

            // 3. Shockwave knockback & damage within 5 blocks
            Location center = player.getLocation();
            center.getWorld().spawnParticle(Particle.FLAME, center.clone().add(0, 1, 0), 45, 1.5, 0.5, 1.5, 0.08);
            center.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, center.clone().add(0, 1, 0), 1);
            center.getWorld().playSound(center, Sound.BLOCK_ANVIL_LAND, 1.4F, 0.7F);

            for (Entity nearby : center.getWorld().getNearbyEntities(center, 5.0D, 4.0D, 5.0D)) {
                if (nearby instanceof LivingEntity target && !target.equals(player) && !(target instanceof ArmorStand)) {
                    if (target instanceof Player tp && (tp.getGameMode() == org.bukkit.GameMode.SPECTATOR
                            || tp.getGameMode() == org.bukkit.GameMode.CREATIVE)) {
                        continue;
                    }
                    // Don't damage Zhitak Seller
                    if (target.getPersistentDataContainer().has(new NamespacedKey(plugin, "blacksmith_seller"), PersistentDataType.BYTE)) {
                        continue;
                    }
                    Vector push = target.getLocation().toVector().subtract(center.toVector());
                    if (push.lengthSquared() < 0.01D) {
                        push = new Vector(0.5D, 0.4D, 0.5D);
                    } else {
                        push.normalize().multiply(1.4D).setY(0.45D);
                    }
                    target.setVelocity(push);
                    target.damage(10.0D, player);
                    target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 50, 0));
                }
            }

            player.sendMessage(Component.text("⚒ Сработала Печать Раскалённой Наковальни! Враги отброшены, а ваша броня восстановлена на +15%!", NamedTextColor.GOLD));
        }, 1L);
    }
}
