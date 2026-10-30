package me.fokend.greatblacksmith;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Campfire;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.PiglinAbstract;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Trident;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Warden;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

public final class FokendGreatBlacksmith extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final long SPAWN_INTERVAL_MILLIS = 60L * 60L * 1000L; // 1 hour
    private static final long WAITING_START_DELAY_MILLIS = 5_000L;       // 5 sec after first player enters
    private static final long FAILED_DESPAWN_MILLIS = 60_000L;           // 1 minute on failure
    private static final long REWARD_SHOP_MILLIS = 3L * 60L * 1000L;     // 3 minutes for shopping
    private static final long UNCLAIMED_TIMEOUT_MILLIS = 50L * 60L * 1000L;

    // Параметры пакетной (batch) постройки и отката арены: не более N блоков и не более X мс за тик
    private static final int BATCH_MAX_OPS_PER_TICK = 3000;
    private static final long BATCH_BUDGET_NANOS = 4_000_000L; // 4 мс из 50 мс тика
    private static final int SEARCH_ATTEMPTS = 25;

    private static final int WASTELAND_RADIUS = 24;
    private static final int CLEAR_HEIGHT = 12;

    private final MiniMessage mm = MiniMessage.miniMessage();
    private NamespacedKey dungeonMobKey;
    private NamespacedKey sellerKey;
    private NamespacedKey coreIdKey;
    private NamespacedKey specialItemKey;

    private BukkitTask mainTask;
    private long nextScheduledSpawnAt;
    private DungeonSession activeDungeon;
    private AnvilOverlevelListener anvilListener;
    private BlacksmithCombatAndPotionListener combatListener;
    private BukkitTask batchTask;
    private volatile boolean shuttingDown;
    private boolean searchingDungeon;
    private final List<RestoreJob> restoreJobs = new ArrayList<>();
    private final Map<Material, BlockData> blockDataCache = new EnumMap<>(Material.class);

    private enum PlaceMode { SET, CLEAR_IF_NOT_AIR, FILL_IF_EMPTY }

    private record PendingBlock(int x, int y, int z, BlockData data, PlaceMode mode) {}

    /** Пошаговый откат арены: фаза 0 — убрать хрупкие блоки, 1 — вернуть BlockData, 2 — вернуть TileState. */
    private static final class RestoreJob {
        final DungeonSession session;
        final List<Map.Entry<Location, BlockData>> entries;
        final List<BlockState> tiles;
        int phase = 0;
        int index = 0;

        RestoreJob(DungeonSession session, List<Map.Entry<Location, BlockData>> entries, List<BlockState> tiles) {
            this.session = session;
            this.entries = entries;
            this.tiles = tiles;
        }

        /** @return true, когда откат полностью завершён */
        boolean step(long deadlineNanos, int maxOps) {
            int ops = 0;
            while (ops < maxOps && System.nanoTime() < deadlineNanos) {
                if (phase == 0) {
                    if (index >= entries.size()) { phase = 1; index = 0; continue; }
                    Block b = entries.get(index++).getKey().getBlock();
                    if (!b.getType().isSolid() && !b.getType().isAir()) {
                        b.setType(Material.AIR, false);
                    }
                } else if (phase == 1) {
                    if (index >= entries.size()) { phase = 2; index = 0; continue; }
                    Map.Entry<Location, BlockData> e = entries.get(index++);
                    e.getKey().getBlock().setBlockData(e.getValue(), false);
                } else if (phase == 2) {
                    if (index >= tiles.size()) { phase = 3; return true; }
                    tiles.get(index++).update(true, false);
                } else {
                    return true;
                }
                ops++;
            }
            return phase >= 3;
        }
    }

    private enum DungeonState {
        WAITING,
        COUNTDOWN,
        WAVES,
        FAILED,
        REWARD
    }

    private static final class DungeonSession {
        final World world;
        final Location center;
        final Map<Location, BlockData> originalBlocks = new LinkedHashMap<>();
        final Map<Location, BlockState> tileStates = new LinkedHashMap<>();
        final ArrayDeque<PendingBlock> buildQueue = new ArrayDeque<>();
        final List<int[]> chunkTickets = new ArrayList<>();
        boolean building;
        UUID teleportOnReady;
        Merchant merchant;
        final Set<String> protectedBlockKeys = new HashSet<>();
        final List<Location> shutterLocations = new ArrayList<>();
        final Set<UUID> participants = new HashSet<>();
        final Set<UUID> spawnedMobs = new HashSet<>();
        final Set<UUID> helperEntities = new HashSet<>();
        final List<MerchantRecipe> trades = new ArrayList<>();
        final BossBar bossBar;

        DungeonState state = DungeonState.WAITING;
        long createdAtMillis;
        long stateDeadlineMillis;
        int currentWave = 0;
        int totalWaves = 4;
        UUID sellerId;
        UUID hologramId;

        DungeonSession(World world, Location center, BossBar bossBar) {
            this.world = world;
            this.center = center;
            this.bossBar = bossBar;
            this.createdAtMillis = System.currentTimeMillis();
        }
    }

    @Override
    public void onEnable() {
        dungeonMobKey = new NamespacedKey(this, "blacksmith_mob");
        sellerKey = new NamespacedKey(this, "blacksmith_seller");
        coreIdKey = new NamespacedKey(this, "upgrade_core_id");
        specialItemKey = new NamespacedKey(this, "blacksmith_special");

        anvilListener = new AnvilOverlevelListener(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(anvilListener, this);
        combatListener = new BlacksmithCombatAndPotionListener(this);
        getServer().getPluginManager().registerEvents(combatListener, this);

        PluginCommand cmd = getCommand("blacksmith");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        nextScheduledSpawnAt = System.currentTimeMillis() + SPAWN_INTERVAL_MILLIS;
        mainTask = Bukkit.getScheduler().runTaskTimer(this, this::tickDungeonSystem, 20L, 10L);
        batchTask = Bukkit.getScheduler().runTaskTimer(this, this::tickBatchWork, 1L, 1L);

        getLogger().info("FokendGreatBlacksmith успешно запущен!");
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        if (mainTask != null) {
            mainTask.cancel();
            mainTask = null;
        }
        if (batchTask != null) {
            batchTask.cancel();
            batchTask = null;
        }
        if (activeDungeon != null) {
            cleanupDungeon(activeDungeon, false);
            activeDungeon = null;
        }
        // Планировщик после отключения недоступен — дорабатываем откат синхронно, чтобы не оставить арену в мире
        drainRestoreJobs();
        if (anvilListener != null) {
            anvilListener.clearAll();
        }
        if (combatListener != null) {
            combatListener.clearAll();
        }
    }

    private void tickDungeonSystem() {
        long now = System.currentTimeMillis();

        if (activeDungeon == null) {
            if (now >= nextScheduledSpawnAt && !searchingDungeon) {
                nextScheduledSpawnAt = now + SPAWN_INTERVAL_MILLIS;
                beginDungeonSearch(ok -> {
                    if (!ok) getLogger().warning("Не удалось подготовить место для данджа Великого Кузнеца.");
                });
            }
            return;
        }

        DungeonSession session = activeDungeon;
        if (session.building) return; // арена ещё строится по тикам
        updateBossBarViewers(session);

        switch (session.state) {
            case WAITING -> {
                if (now - session.createdAtMillis >= UNCLAIMED_TIMEOUT_MILLIS) {
                    Bukkit.broadcast(Component.text(
                            "[Великий Кузнец] Никто не осмелился войти на пустырь. Дандж исчез.",
                            NamedTextColor.GRAY));
                    cleanupDungeon(session, true);
                    activeDungeon = null;
                    return;
                }
                List<Player> inside = getValidPlayersOnWasteland(session);
                if (!inside.isEmpty()) {
                    session.state = DungeonState.COUNTDOWN;
                    session.stateDeadlineMillis = now + WAITING_START_DELAY_MILLIS;
                    for (Player p : inside) {
                        session.participants.add(p.getUniqueId());
                        p.sendMessage(Component.text(
                                "⚔ Вы ступили на пустырь Великого Кузнеца! Волны начнутся через 5 секунд. Не покидайте пустырь!",
                                NamedTextColor.GOLD));
                        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.8F, 0.8F);
                    }
                }
                session.bossBar.name(Component.text(
                        "⚔ Пустырь Великого Кузнеца [" + session.center.getBlockX() + ", "
                                + session.center.getBlockY() + ", " + session.center.getBlockZ() + "] — Ожидание игроков",
                        NamedTextColor.GOLD));
                session.bossBar.progress(1.0F);
                session.bossBar.color(BossBar.Color.YELLOW);
            }

            case COUNTDOWN -> {
                List<Player> inside = getValidPlayersOnWasteland(session);
                if (inside.isEmpty()) {
                    triggerFailure(session, "в зоне данжа не осталось игроков");
                    return;
                }
                for (Player p : inside) {
                    session.participants.add(p.getUniqueId());
                }
                long rem = Math.max(0L, session.stateDeadlineMillis - now);
                float progress = Math.max(0.0F, Math.min(1.0F, (float) rem / (float) WAITING_START_DELAY_MILLIS));
                session.bossBar.name(Component.text(
                        "⚔ Подготовка к бою: " + ((rem + 999L) / 1000L) + " сек. (Игроков в зоне: " + inside.size() + ")",
                        NamedTextColor.YELLOW));
                session.bossBar.progress(progress);
                if (now >= session.stateDeadlineMillis) {
                    startWaveCombat(session);
                }
            }

            case WAVES -> {
                List<Player> inside = getValidPlayersOnWasteland(session);
                // Проигрыш считается ТОЛЬКО когда в зоне данжа не осталось игроков
                if (inside.isEmpty()) {
                    triggerFailure(session, "в зоне данжа не осталось игроков");
                    return;
                }

                // Add any new players who entered the wasteland mid-fight and scale total waves up if needed
                for (Player p : inside) {
                    if (session.participants.add(p.getUniqueId())) {
                        int recalculatedWaves = calculateTotalWaves(session.participants.size());
                        if (recalculatedWaves > session.totalWaves) {
                            session.totalWaves = recalculatedWaves;
                        }
                        p.sendMessage(Component.text(
                                "⚔ Вы присоединились к битве на пустыре! Сложность увеличена!",
                                NamedTextColor.RED));
                    }
                }

                // Maintain active mobs (remove dead/invalid, keep inside wasteland, keep Warden angry)
                maintainDungeonMobs(session);

                if (session.spawnedMobs.isEmpty()) {
                    if (session.currentWave >= session.totalWaves) {
                        triggerVictory(session);
                    } else {
                        spawnNextWave(session);
                    }
                } else {
                    int aliveCount = session.spawnedMobs.size();
                    session.bossBar.name(Component.text(
                            "⚔ Волна " + session.currentWave + "/" + session.totalWaves
                                    + " | Осталось монстров: " + aliveCount
                                    + " | Бойцов в зоне: " + inside.size(),
                            NamedTextColor.RED));
                    session.bossBar.color(BossBar.Color.RED);
                    float prog = Math.max(0.05F, Math.min(1.0F, (float) session.currentWave / (float) session.totalWaves));
                    session.bossBar.progress(prog);
                }
            }

            case FAILED -> {
                long rem = Math.max(0L, session.stateDeadlineMillis - now);
                long sec = (rem + 999L) / 1000L;
                session.bossBar.name(Component.text(
                        "✖ ПРОВАЛ! Дандж исчезнет через " + sec + " сек.",
                        NamedTextColor.DARK_RED));
                session.bossBar.color(BossBar.Color.RED);
                session.bossBar.progress(Math.max(0.0F, Math.min(1.0F, (float) rem / (float) FAILED_DESPAWN_MILLIS)));
                if (now >= session.stateDeadlineMillis) {
                    Bukkit.broadcast(Component.text(
                            "[Великий Кузнец] Пустырь и трейлер исчезли после провала.",
                            NamedTextColor.RED));
                    cleanupDungeon(session, true);
                    activeDungeon = null;
                }
            }

            case REWARD -> {
                long rem = Math.max(0L, session.stateDeadlineMillis - now);
                long sec = (rem + 999L) / 1000L;
                long mins = sec / 60L;
                long remSec = sec % 60L;
                session.bossBar.name(Component.text(
                        String.format(Locale.ROOT, "✦ Лавка Великого Кузнеца открыта! Осталось %d:%02d", mins, remSec),
                        NamedTextColor.GREEN));
                session.bossBar.color(BossBar.Color.GREEN);
                session.bossBar.progress(Math.max(0.0F, Math.min(1.0F, (float) rem / (float) REWARD_SHOP_MILLIS)));
                if (now >= session.stateDeadlineMillis) {
                    Bukkit.broadcast(Component.text(
                            "[Великий Кузнец] Время закупки истекло! Трейлер Великого Кузнеца уехал.",
                            NamedTextColor.GOLD));
                    cleanupDungeon(session, true);
                    activeDungeon = null;
                }
            }
        }
    }

    private void updateBossBarViewers(DungeonSession session) {
        double viewRadiusSq = (WASTELAND_RADIUS + 35.0D) * (WASTELAND_RADIUS + 35.0D);
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean shouldSee = player.getWorld().equals(session.world)
                    && player.getLocation().distanceSquared(session.center) <= viewRadiusSq;
            if (shouldSee) {
                player.showBossBar(session.bossBar);
            } else {
                player.hideBossBar(session.bossBar);
            }
        }
    }

    private List<Player> getValidPlayersOnWasteland(DungeonSession session) {
        List<Player> list = new ArrayList<>();
        for (Player player : session.world.getPlayers()) {
            if (player.isDead() || !player.isValid()) continue;
            if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) continue;
            if (isInsideWasteland(session, player.getLocation())) {
                list.add(player);
            }
        }
        return list;
    }

    private boolean isInsideWasteland(DungeonSession session, Location loc) {
        if (loc == null || loc.getWorld() == null || !loc.getWorld().equals(session.world)) return false;
        double dx = loc.getX() - (session.center.getBlockX() + 0.5D);
        double dz = loc.getZ() - (session.center.getBlockZ() + 0.5D);
        double dy = Math.abs(loc.getY() - session.center.getBlockY());
        return (dx * dx + dz * dz) <= (WASTELAND_RADIUS + 0.75D) * (WASTELAND_RADIUS + 0.75D) && dy <= 16.0D;
    }

    private String findLeaverName(DungeonSession session) {
        for (UUID id : session.participants) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                return "Участник";
            }
            if (p.isDead() || !isInsideWasteland(session, p.getLocation())) {
                return p.getName();
            }
        }
        return null;
    }

    private int calculateTotalWaves(int playerCount) {
        int n = Math.max(1, playerCount);
        return Math.min(8, 3 + n); // 1 player = 4 waves, 2 = 5 waves, 3 = 6 waves, 4 = 7 waves, 5+ = 8 waves
    }

    private void startWaveCombat(DungeonSession session) {
        int playerCount = Math.max(1, session.participants.size());
        session.totalWaves = calculateTotalWaves(playerCount);
        session.currentWave = 0;
        session.state = DungeonState.WAVES;
        spawnNextWave(session);
    }

    private void spawnNextWave(DungeonSession session) {
        session.currentWave++;
        int wave = session.currentWave;
        int players = Math.max(1, session.participants.size());

        // More players = more mobs and stronger composition
        int mobCount = (4 + wave * 2) + (players - 1) * (3 + wave);

        session.world.playSound(session.center, Sound.EVENT_RAID_HORN, 2.0F, 0.9F);
        for (UUID id : session.participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                p.sendMessage(Component.text(
                        "⚔ [Великий Кузнец] Началась волна " + wave + " из " + session.totalWaves
                                + "! (Монстров: " + mobCount + ")",
                        NamedTextColor.GOLD));
            }
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        boolean wardenSpawnedThisWave = false;

        for (int i = 0; i < mobCount; i++) {
            EntityType type;
            // Spawn a Warden on final wave (or waves >= 4) as a non-boss heavyweight!
            if (!wardenSpawnedThisWave && (wave == session.totalWaves || (wave >= 4 && players >= 2))) {
                type = EntityType.WARDEN;
                wardenSpawnedThisWave = true;
            } else {
                type = pickStrongMobType(wave, session.totalWaves, rnd);
            }

            Location spawnLoc = pickMobSpawnLocation(session, rnd);
            Entity entity = session.world.spawnEntity(spawnLoc, type, CreatureSpawnEvent.SpawnReason.CUSTOM, spawned -> {
                spawned.getPersistentDataContainer().set(dungeonMobKey, PersistentDataType.BYTE, (byte) 1);
                spawned.setPersistent(false);
                if (spawned instanceof LivingEntity le) {
                    le.setRemoveWhenFarAway(false);
                    equipAndBuffDungeonMob(le, wave, players, rnd);
                }
                if (spawned instanceof Mob mob) {
                    Player target = pickRandomParticipant(session, rnd);
                    if (target != null) {
                        mob.setTarget(target);
                    }
                }
                if (spawned instanceof Warden warden) {
                    for (UUID pid : session.participants) {
                        Player p = Bukkit.getPlayer(pid);
                        if (p != null && p.isOnline()) {
                            warden.increaseAnger(p, 120);
                        }
                    }
                }
            });
            session.spawnedMobs.add(entity.getUniqueId());
            session.world.spawnParticle(Particle.SOUL_FIRE_FLAME, spawnLoc.clone().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.02);
        }
    }

    private EntityType pickStrongMobType(int wave, int totalWaves, ThreadLocalRandom rnd) {
        EntityType[] tier1 = {
                EntityType.VINDICATOR,
                EntityType.PILLAGER,
                EntityType.WITCH,
                EntityType.WITHER_SKELETON,
                EntityType.BLAZE,
                EntityType.BREEZE,
                EntityType.BOGGED,
                EntityType.STRAY,
                EntityType.HUSK,
                EntityType.CREEPER
        };
        EntityType[] tier2 = {
                EntityType.VINDICATOR,
                EntityType.PIGLIN_BRUTE,
                EntityType.WITHER_SKELETON,
                EntityType.EVOKER,
                EntityType.RAVAGER,
                EntityType.BREEZE,
                EntityType.BLAZE,
                EntityType.WITCH,
                EntityType.ENDERMAN
        };
        EntityType[] tier3 = {
                EntityType.PIGLIN_BRUTE,
                EntityType.RAVAGER,
                EntityType.EVOKER,
                EntityType.VINDICATOR,
                EntityType.WITHER_SKELETON,
                EntityType.BREEZE,
                EntityType.ELDER_GUARDIAN
        };

        if (wave >= totalWaves || wave >= 4) {
            return rnd.nextBoolean() ? tier3[rnd.nextInt(tier3.length)] : tier2[rnd.nextInt(tier2.length)];
        } else if (wave >= 2) {
            return rnd.nextBoolean() ? tier2[rnd.nextInt(tier2.length)] : tier1[rnd.nextInt(tier1.length)];
        } else {
            return tier1[rnd.nextInt(tier1.length)];
        }
    }

    private void equipAndBuffDungeonMob(LivingEntity le, int wave, int players, ThreadLocalRandom rnd) {
        if (le instanceof PiglinAbstract piglin) {
            piglin.setImmuneToZombification(true);
        }
        if (le instanceof Zombie zombie) {
            zombie.setShouldBurnInDay(false);
        }
        try {
            // Paper API method on AbstractSkeleton / Phantom / etc.
            le.getClass().getMethod("setShouldBurnInDay", boolean.class).invoke(le, false);
        } catch (ReflectiveOperationException ignored) {
        }

        // Scale health slightly with player count and wave (except Warden which already has 500 HP)
        if (!(le instanceof Warden)) {
            AttributeInstance maxHp = le.getAttribute(Attribute.MAX_HEALTH);
            if (maxHp != null) {
                double multiplier = 1.0D + (wave - 1) * 0.15D + (players - 1) * 0.20D;
                double newMax = Math.min(250.0D, Math.round(maxHp.getBaseValue() * multiplier));
                maxHp.setBaseValue(newMax);
                le.setHealth(newMax);
            }
        }

        EntityEquipment eq = le.getEquipment();
        if (eq != null) {
            eq.setHelmetDropChance(0.0F);
            eq.setChestplateDropChance(0.0F);
            eq.setLeggingsDropChance(0.0F);
            eq.setBootsDropChance(0.0F);
            eq.setItemInMainHandDropChance(0.0F);
            eq.setItemInOffHandDropChance(0.0F);

            if (le.getType() == EntityType.WITHER_SKELETON || le.getType() == EntityType.PIGLIN_BRUTE || le.getType() == EntityType.VINDICATOR) {
                if (wave >= 3 && rnd.nextBoolean()) {
                    eq.setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
                    eq.setBoots(new ItemStack(Material.DIAMOND_BOOTS));
                } else if (wave >= 2 && rnd.nextBoolean()) {
                    eq.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
                }
            }
        }
    }

    private Location pickMobSpawnLocation(DungeonSession session, ThreadLocalRandom rnd) {
        for (int attempt = 0; attempt < 20; attempt++) {
            double angle = rnd.nextDouble() * Math.PI * 2.0D;
            double dist = 9.0D + rnd.nextDouble() * (WASTELAND_RADIUS - 11.0D);
            int x = session.center.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int z = session.center.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);
            int y = session.center.getBlockY() + 1;
            Location candidate = new Location(session.world, x + 0.5D, y, z + 0.5D);
            if (candidate.getBlock().getType().isAir() && candidate.clone().add(0, 1, 0).getBlock().getType().isAir()) {
                return candidate;
            }
        }
        return session.center.clone().add(10.5D, 1.0D, 0.5D);
    }

    private Player pickRandomParticipant(DungeonSession session, ThreadLocalRandom rnd) {
        List<Player> valid = new ArrayList<>();
        for (UUID id : session.participants) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline() && !p.isDead()) {
                valid.add(p);
            }
        }
        if (valid.isEmpty()) return null;
        return valid.get(rnd.nextInt(valid.size()));
    }

    private void maintainDungeonMobs(DungeonSession session) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        session.spawnedMobs.removeIf(uuid -> {
            Entity ent = Bukkit.getEntity(uuid);
            if (!(ent instanceof LivingEntity le) || !le.isValid() || le.isDead()) {
                return true;
            }
            // Keep mobs inside the wasteland radius
            Location loc = le.getLocation();
            double dx = loc.getX() - (session.center.getBlockX() + 0.5D);
            double dz = loc.getZ() - (session.center.getBlockZ() + 0.5D);
            if (!loc.getWorld().equals(session.world)
                    || (dx * dx + dz * dz) > (WASTELAND_RADIUS - 1.5D) * (WASTELAND_RADIUS - 1.5D)
                    || loc.getY() < session.center.getBlockY() - 3) {
                Location pullBack = pickMobSpawnLocation(session, rnd);
                le.teleport(pullBack);
            }
            if (le instanceof Mob mob) {
                LivingEntity target = mob.getTarget();
                if (!(target instanceof Player tp) || !session.participants.contains(tp.getUniqueId()) || tp.isDead()) {
                    Player nextTarget = pickRandomParticipant(session, rnd);
                    if (nextTarget != null) {
                        mob.setTarget(nextTarget);
                    }
                }
            }
            if (le instanceof Warden warden) {
                Player target = pickRandomParticipant(session, rnd);
                if (target != null) {
                    warden.increaseAnger(target, 80);
                    warden.setTarget(target);
                }
            }
            return false;
        });
    }

    private void triggerFailure(DungeonSession session, String reason) {
        if (session.state == DungeonState.FAILED) return;
        session.state = DungeonState.FAILED;
        session.stateDeadlineMillis = System.currentTimeMillis() + FAILED_DESPAWN_MILLIS;

        killAllDungeonMobs(session);
        updateShutterHologram(session, Component.text("✖ ПРОВАЛ! " + reason, NamedTextColor.RED));

        Component failMsg = Component.text("✖ [Великий Кузнец] Провал (" + reason + ")! Все монстры погибли, дандж исчезнет через 1 минуту без награды.", NamedTextColor.RED);
        Bukkit.broadcast(failMsg);
        session.world.playSound(session.center, Sound.ENTITY_WITHER_SPAWN, 1.2F, 0.7F);
    }

    private void triggerVictory(DungeonSession session) {
        session.state = DungeonState.REWARD;
        session.stateDeadlineMillis = System.currentTimeMillis() + REWARD_SHOP_MILLIS;

        killAllDungeonMobs(session);
        generateRandomBlacksmithTrades(session);
        openTrailerShutter(session);

        Component winMsg = Component.text(
                "✦ [Великий Кузнец] Все волны отбиты! Шторка трейлера открылась — у вас есть 3 минуты на закупку у Великого Кузнеца!",
                NamedTextColor.GREEN);
        Bukkit.broadcast(winMsg);
        session.world.playSound(session.center, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.5F, 1.0F);
        session.world.playSound(session.center, Sound.BLOCK_IRON_DOOR_OPEN, 1.5F, 0.8F);
        session.world.spawnParticle(
                Particle.TOTEM_OF_UNDYING,
                session.center.clone().add(0.5D, 3.0D, 3.5D),
                60, 1.2D, 0.8D, 0.8D, 0.15D);
    }

    private void killAllDungeonMobs(DungeonSession session) {
        for (UUID id : new ArrayList<>(session.spawnedMobs)) {
            Entity ent = Bukkit.getEntity(id);
            if (ent != null) {
                session.world.spawnParticle(Particle.SMOKE, ent.getLocation().add(0, 1, 0), 12, 0.3, 0.4, 0.3, 0.02);
                ent.remove();
            }
        }
        session.spawnedMobs.clear();
    }

    // =========================================================================
    // DUNGEON & TRAILER BUILDER / RESTORATION
    // =========================================================================

    private void runOnMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        if (shuttingDown || !isEnabled()) return;
        Bukkit.getScheduler().runTask(this, task);
    }

    private BlockData blockData(Material material) {
        return blockDataCache.computeIfAbsent(material, Bukkit::createBlockData);
    }

    /** Асинхронный поиск места: чанки грузятся через World#getChunkAtAsync, главный поток не блокируется. */
    private boolean beginDungeonSearch(Consumer<Boolean> callback) {
        if (searchingDungeon) return false;
        World world = Bukkit.getWorlds().stream()
                .filter(w -> w.getEnvironment() == World.Environment.NORMAL)
                .findFirst()
                .orElse(Bukkit.getWorlds().getFirst());
        searchingDungeon = true;
        searchSurfaceAttempt(world, 0, callback);
        return true;
    }

    private void searchSurfaceAttempt(World world, int attempt, Consumer<Boolean> callback) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Location spawn = world.getSpawnLocation();
        final boolean fallback = attempt >= SEARCH_ATTEMPTS;
        int x;
        int z;
        if (fallback) {
            x = spawn.getBlockX() + 180;
            z = spawn.getBlockZ() + 180;
        } else {
            int offsetX = rnd.nextInt(-600, 601);
            int offsetZ = rnd.nextInt(-600, 601);
            if (Math.abs(offsetX) < 120 && Math.abs(offsetZ) < 120) {
                offsetX += (offsetX >= 0 ? 150 : -150);
            }
            x = spawn.getBlockX() + offsetX;
            z = spawn.getBlockZ() + offsetZ;
        }
        final int fx = x;
        final int fz = z;

        world.getChunkAtAsync(fx >> 4, fz >> 4, true).whenComplete((chunk, error) -> runOnMain(() -> {
            if (shuttingDown) {
                searchingDungeon = false;
                return;
            }
            if (error != null || chunk == null) {
                if (fallback) {
                    searchingDungeon = false;
                    callback.accept(false);
                } else {
                    searchSurfaceAttempt(world, attempt + 1, callback);
                }
                return;
            }
            Block highest = world.getHighestBlockAt(fx, fz);
            boolean bad = highest.isLiquid()
                    || highest.getY() < world.getMinHeight() + 10
                    || highest.getY() > world.getMaxHeight() - 25;
            if (bad && !fallback) {
                searchSurfaceAttempt(world, attempt + 1, callback);
                return;
            }
            int y = fallback ? Math.max(64, highest.getY()) : highest.getY();
            preloadArenaAndBuild(world, new Location(world, fx, y, fz), null, callback);
        }));
    }

    /** Подгружает все чанки арены асинхронно, закрепляет их тикетами и только потом ставит постройку в очередь. */
    private void preloadArenaAndBuild(World world, Location center, UUID teleportPlayer, Consumer<Boolean> callback) {
        int reach = WASTELAND_RADIUS + 3;
        int minCx = (center.getBlockX() - reach) >> 4;
        int maxCx = (center.getBlockX() + reach) >> 4;
        int minCz = (center.getBlockZ() - reach) >> 4;
        int maxCz = (center.getBlockZ() + reach) >> 4;

        List<int[]> coords = new ArrayList<>();
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (int chx = minCx; chx <= maxCx; chx++) {
            for (int chz = minCz; chz <= maxCz; chz++) {
                coords.add(new int[]{chx, chz});
                futures.add(world.getChunkAtAsync(chx, chz, true));
            }
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((v, error) -> runOnMain(() -> {
            searchingDungeon = false;
            if (shuttingDown) return;
            if (error != null) {
                getLogger().warning("Не удалось загрузить чанки арены: " + error.getMessage());
                callback.accept(false);
                return;
            }
            List<int[]> tickets = new ArrayList<>();
            for (int[] c : coords) {
                if (world.addPluginChunkTicket(c[0], c[1], this)) {
                    tickets.add(c);
                }
            }
            beginDungeonBuild(world, center, tickets, teleportPlayer);
            callback.accept(true);
        }));
    }

    /** Команда /blacksmith here: арена строится на позиции игрока, но тоже асинхронно и по тикам. */
    private boolean beginDungeonAt(Location rawCenter, UUID teleportPlayer, Consumer<Boolean> callback) {
        World world = rawCenter.getWorld();
        if (world == null || searchingDungeon) return false;
        searchingDungeon = true;
        preloadArenaAndBuild(world, new Location(world, rawCenter.getBlockX(), rawCenter.getBlockY(), rawCenter.getBlockZ()),
                teleportPlayer, callback);
        return true;
    }

    private void beginDungeonBuild(World world, Location center, List<int[]> tickets, UUID teleportPlayer) {
        drainRestoreJobs();
        if (activeDungeon != null) {
            cleanupDungeon(activeDungeon, false);
            activeDungeon = null;
            drainRestoreJobs();
        }

        BossBar bar = BossBar.bossBar(
                Component.text("⚔ Пустырь Великого Кузнеца", NamedTextColor.GOLD),
                1.0F,
                BossBar.Color.YELLOW,
                BossBar.Overlay.NOTCHED_10);

        DungeonSession session = new DungeonSession(world, center, bar);
        session.chunkTickets.addAll(tickets);
        session.teleportOnReady = teleportPlayer;
        session.building = true;
        buildWastelandAndTrailer(session); // только формирует очередь, мир не трогает
        activeDungeon = session;
    }

    /** Один тик пакетной работы: либо постройка арены, либо откат. Ограничено по числу блоков и по времени. */
    private void tickBatchWork() {
        long deadline = System.nanoTime() + BATCH_BUDGET_NANOS;
        DungeonSession session = activeDungeon;
        if (session != null && session.building) {
            int ops = 0;
            while (!session.buildQueue.isEmpty() && ops < BATCH_MAX_OPS_PER_TICK && System.nanoTime() < deadline) {
                applyPending(session, session.buildQueue.poll());
                ops++;
            }
            if (session.buildQueue.isEmpty()) {
                finishBuild(session);
            }
            return;
        }
        if (!restoreJobs.isEmpty()) {
            RestoreJob job = restoreJobs.getFirst();
            if (job.step(deadline, BATCH_MAX_OPS_PER_TICK)) {
                restoreJobs.remove(job);
                releaseTickets(job.session);
            }
        }
    }

    private void drainRestoreJobs() {
        for (RestoreJob job : new ArrayList<>(restoreJobs)) {
            while (!job.step(Long.MAX_VALUE, Integer.MAX_VALUE)) {
                // синхронно доводим до конца
            }
            releaseTickets(job.session);
        }
        restoreJobs.clear();
    }

    private void releaseTickets(DungeonSession session) {
        for (int[] c : session.chunkTickets) {
            session.world.removePluginChunkTicket(c[0], c[1], this);
        }
        session.chunkTickets.clear();
    }

    private void finishBuild(DungeonSession session) {
        session.building = false;
        session.createdAtMillis = System.currentTimeMillis();
        spawnTrailerEntities(session);

        int cx = session.center.getBlockX();
        int cy = session.center.getBlockY();
        int cz = session.center.getBlockZ();
        Component announcement = Component.text("=============================================\n", NamedTextColor.DARK_GRAY)
                .append(Component.text("⚒ ДАНДЖ: ВЕЛИКИЙ КУЗНЕЦ ПОЯВИЛСЯ В МИРЕ!\n", NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text("📍 Координаты пустыря: ", NamedTextColor.YELLOW))
                .append(Component.text("X: " + cx + ", Y: " + cy + ", Z: " + cz + "\n", NamedTextColor.AQUA, TextDecoration.BOLD))
                .append(Component.text("⚠ На пустыре не работают булавы и копья (кроме Морозного копья)!\n", NamedTextColor.RED))
                .append(Component.text("=============================================", NamedTextColor.DARK_GRAY));
        Bukkit.broadcast(announcement);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_PLACE, 0.8F, 0.7F);
        }
        if (session.teleportOnReady != null) {
            Player target = Bukkit.getPlayer(session.teleportOnReady);
            if (target != null && target.isOnline()) {
                target.teleport(session.center.clone().add(0.5D, 1.2D, 10.5D));
            }
            session.teleportOnReady = null;
        }
    }

    private void queueOp(DungeonSession session, int x, int y, int z, BlockData data, PlaceMode mode) {
        session.buildQueue.add(new PendingBlock(x, y, z, data, mode));
    }

    private void queueBlock(DungeonSession session, int x, int y, int z, BlockData data) {
        queueOp(session, x, y, z, data, PlaceMode.SET);
    }

    private void queueMaterial(DungeonSession session, int x, int y, int z, Material mat) {
        queueOp(session, x, y, z, blockData(mat), PlaceMode.SET);
    }

    /** Применение одного элемента очереди: все чтения мира происходят здесь, а не при генерации плана. */
    private void applyPending(DungeonSession session, PendingBlock pb) {
        Block block = session.world.getBlockAt(pb.x(), pb.y(), pb.z());
        switch (pb.mode()) {
            case CLEAR_IF_NOT_AIR -> {
                if (block.getType().isAir()) {
                    session.protectedBlockKeys.add(blockKey(block));
                    return;
                }
            }
            case FILL_IF_EMPTY -> {
                if (!block.getType().isAir() && !block.isLiquid()) return;
            }
            default -> { }
        }
        applyDungeonBlock(session, block, pb.data());
    }

    private void applyDungeonBlock(DungeonSession session, Block block, BlockData newData) {
        if (block.getType() == Material.BEDROCK || block.getType() == Material.END_PORTAL_FRAME || block.getType() == Material.END_PORTAL) {
            return;
        }
        Location loc = block.getLocation();
        if (!session.originalBlocks.containsKey(loc)) {
            session.originalBlocks.put(loc, block.getBlockData().clone());
        }
        session.protectedBlockKeys.add(blockKey(block));

        // Сохраняем снапшот TileState (сундуки, таблички, бочки...), чтобы после отката
        // вернуть содержимое: setBlockData молча уничтожает инвентарь контейнера.
        if (!session.tileStates.containsKey(loc)) {
            BlockState state = block.getState();
            if (state instanceof TileState) {
                session.tileStates.put(loc, state);
            }
        }
        block.setBlockData(newData, false);
    }

    /** Немедленная запись (мелкие операции после постройки, напр. открытие шторки). */
    private void setDungeonBlock(DungeonSession session, int x, int y, int z, BlockData newData) {
        applyDungeonBlock(session, session.world.getBlockAt(x, y, z), newData);
    }

    private void setDungeonMaterial(DungeonSession session, int x, int y, int z, Material mat) {
        setDungeonBlock(session, x, y, z, blockData(mat));
    }

    private void buildWastelandAndTrailer(DungeonSession session) {
        int cx = session.center.getBlockX();
        int cy = session.center.getBlockY();
        int cz = session.center.getBlockZ();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        Material[] wastelandFloor = {
                Material.COARSE_DIRT, Material.COARSE_DIRT, Material.COARSE_DIRT,
                Material.PODZOL, Material.GRAVEL, Material.TUFF,
                Material.COBBLESTONE, Material.SOUL_SOIL
        };

        // 1. Clear airspace first (top-down so attached blocks/leaves don't drop items)
        for (int dy = CLEAR_HEIGHT; dy >= 1; dy--) {
            for (int dx = -WASTELAND_RADIUS; dx <= WASTELAND_RADIUS; dx++) {
                for (int dz = -WASTELAND_RADIUS; dz <= WASTELAND_RADIUS; dz++) {
                    if (dx * dx + dz * dz > WASTELAND_RADIUS * WASTELAND_RADIUS) continue;
                    queueOp(session, cx + dx, cy + dy, cz + dz, blockData(Material.AIR), PlaceMode.CLEAR_IF_NOT_AIR);
                }
            }
        }

        // 2. Build Wasteland foundation & floor
        for (int dx = -WASTELAND_RADIUS; dx <= WASTELAND_RADIUS; dx++) {
            for (int dz = -WASTELAND_RADIUS; dz <= WASTELAND_RADIUS; dz++) {
                int distSq = dx * dx + dz * dz;
                if (distSq > WASTELAND_RADIUS * WASTELAND_RADIUS) continue;

                // Sub-foundation (fill air/liquid pockets 3 blocks below)
                for (int subY = cy - 3; subY < cy; subY++) {
                    queueOp(session, cx + dx, subY, cz + dz, blockData(Material.COBBLESTONE), PlaceMode.FILL_IF_EMPTY);
                }

                double dist = Math.sqrt(distSq);
                if (dist >= WASTELAND_RADIUS - 1.2D) {
                    // Perimeter border ring
                    queueMaterial(session, cx + dx, cy, cz + dz, Material.POLISHED_BLACKSTONE_BRICKS);
                } else {
                    Material floorMat = wastelandFloor[rnd.nextInt(wastelandFloor.length)];
                    queueMaterial(session, cx + dx, cy, cz + dz, floorMat);
                    // Occasional dead bushes outside trailer footprint
                    if ((Math.abs(dx) > 6 || Math.abs(dz) > 5) && floorMat == Material.COARSE_DIRT && rnd.nextInt(28) == 0) {
                        queueMaterial(session, cx + dx, cy + 1, cz + dz, Material.DEAD_BUSH);
                    }
                }
            }
        }

        // 3. Perimeter lantern posts at 8 compass points
        int[][] posts = {
                {0, -WASTELAND_RADIUS}, {0, WASTELAND_RADIUS},
                {-WASTELAND_RADIUS, 0}, {WASTELAND_RADIUS, 0},
                {-17, -17}, {17, -17}, {-17, 17}, {17, 17}
        };
        for (int[] p : posts) {
            int px = cx + p[0];
            int pz = cz + p[1];
            queueMaterial(session, px, cy + 1, pz, Material.COBBLED_DEEPSLATE_WALL);
            queueMaterial(session, px, cy + 2, pz, Material.SOUL_LANTERN);
        }

        // 4. Build the Trailer in the center (X: -4..+4, Z: -3..+3)
        buildCentralTrailer(session, cx, cy, cz);
    }

    private void buildCentralTrailer(DungeonSession session, int cx, int cy, int cz) {
        // Wheels at Y+1 (4 wheel pairs)
        int[][] wheelPositions = {
                {-3, -3}, {3, -3},
                {-3, 3}, {3, 3}
        };
        for (int[] w : wheelPositions) {
            queueMaterial(session, cx + w[0], cy + 1, cz + w[1], Material.BLACK_CONCRETE);
        }

        // Trailer chassis/floor at Y+1 (X: -4..+4, Z: -2..+2)
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                queueMaterial(session, cx + dx, cy + 1, cz + dz, Material.DEEPSLATE_TILES);
            }
        }
        // Trailer hitch on the east side (X = +5, Z = 0, Y = +1)
        Slab hitchSlab = (Slab) Bukkit.createBlockData(Material.SMOOTH_STONE_SLAB);
        hitchSlab.setType(Slab.Type.BOTTOM);
        queueBlock(session, cx + 5, cy + 1, cz, hitchSlab);

        // Front counter step at Z = +3, X = -1..+1, Y = +1
        for (int dx = -1; dx <= 1; dx++) {
            Stairs step = (Stairs) Bukkit.createBlockData(Material.POLISHED_DEEPSLATE_STAIRS);
            step.setFacing(BlockFace.NORTH);
            step.setHalf(Bisected.Half.BOTTOM);
            queueBlock(session, cx + dx, cy + 1, cz + 3, step);
        }

        // Cabin walls (Y+2 to Y+4, X: -4..+4, Z: -2..+2)
        for (int dy = 2; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    boolean borderX = (dx == -4 || dx == 4);
                    boolean borderZ = (dz == -2 || dz == 2);
                    if (borderX || borderZ) {
                        // Front shutter opening is at dz == 2, dx in [-1..1], dy in [2..3]
                        if (dz == 2 && dx >= -1 && dx <= 1 && (dy == 2 || dy == 3)) {
                            Location sLoc = new Location(session.world, cx + dx, cy + dy, cz + dz);
                            session.shutterLocations.add(sLoc);
                            queueMaterial(session, cx + dx, cy + dy, cz + dz, Material.IRON_BARS);
                        } else if ((borderX && borderZ) || dy == 4) {
                            queueMaterial(session, cx + dx, cy + dy, cz + dz, Material.POLISHED_DEEPSLATE);
                        } else if (borderX && dz == 0 && dy == 3) {
                            queueMaterial(session, cx + dx, cy + dy, cz + dz, Material.BLACK_STAINED_GLASS_PANE);
                        } else {
                            queueMaterial(session, cx + dx, cy + dy, cz + dz, Material.WAXED_OXIDIZED_CUT_COPPER);
                        }
                    } else {
                        queueMaterial(session, cx + dx, cy + dy, cz + dz, Material.AIR);
                    }
                }
            }
        }

        // Roof at Y+5 (X: -4..+4, Z: -2..+2)
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                Slab roofSlab = (Slab) Bukkit.createBlockData(Material.DEEPSLATE_TILE_SLAB);
                roofSlab.setType(Slab.Type.BOTTOM);
                queueBlock(session, cx + dx, cy + 5, cz + dz, roofSlab);
            }
        }
        // Chimney at (-2, Y+5..Y+6, -1)
        queueMaterial(session, cx - 2, cy + 5, cz - 1, Material.COBBLESTONE_WALL);
        Campfire campfire = (Campfire) Bukkit.createBlockData(Material.CAMPFIRE);
        campfire.setLit(true);
        queueBlock(session, cx - 2, cy + 6, cz - 1, campfire);

        // Interior smithy props at Y+2
        queueMaterial(session, cx - 3, cy + 2, cz - 1, Material.BLAST_FURNACE);
        queueMaterial(session, cx - 2, cy + 2, cz - 1, Material.LAVA_CAULDRON);
        queueMaterial(session, cx + 3, cy + 2, cz - 1, Material.ANVIL);
        queueMaterial(session, cx + 2, cy + 2, cz - 1, Material.SMITHING_TABLE);

        // Hanging lantern inside cabin at (0, Y+4, -1)
        Lantern lantern = (Lantern) Bukkit.createBlockData(Material.LANTERN);
        lantern.setHanging(true);
        queueBlock(session, cx, cy + 4, cz - 1, lantern);
    }

    /** Житак (житель-торговец) и голограмма появляются только после того, как трейлер полностью построен. */
    private void spawnTrailerEntities(DungeonSession session) {
        int cx = session.center.getBlockX();
        int cy = session.center.getBlockY();
        int cz = session.center.getBlockZ();

        // Spawn the Great Blacksmith Seller inside the trailer at (cx + 0.5, cy + 2, cz + 0.5)
        Location sellerLoc = new Location(session.world, cx + 0.5D, cy + 2.0D, cz + 0.5D, 0.0F, 0.0F);
        Villager seller = session.world.spawn(sellerLoc, Villager.class, v -> {
            v.getPersistentDataContainer().set(sellerKey, PersistentDataType.BYTE, (byte) 1);
            v.setProfession(Villager.Profession.WEAPONSMITH);
            v.setVillagerType(Villager.Type.PLAINS);
            v.setVillagerLevel(5);
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setCollidable(false);
            v.setPersistent(false);
            v.setRemoveWhenFarAway(false);
            v.customName(Component.text("✦ Великий Кузнец ✦", NamedTextColor.GOLD, TextDecoration.BOLD));
            v.setCustomNameVisible(true);
        });
        session.sellerId = seller.getUniqueId();
        session.helperEntities.add(seller.getUniqueId());

        // Spawn hologram above the trailer shutter at (cx + 0.5, cy + 4.3, cz + 2.8)
        Location holoLoc = new Location(session.world, cx + 0.5D, cy + 4.3D, cz + 2.8D);
        TextDisplay holo = session.world.spawn(holoLoc, TextDisplay.class, td -> {
            td.text(Component.text("🔒 Трейлер Великого Кузнеца (Отбейте волны!)", NamedTextColor.GOLD, TextDecoration.BOLD));
            td.setBillboard(Display.Billboard.CENTER);
            td.setShadowed(true);
            td.setPersistent(false);
        });
        session.hologramId = holo.getUniqueId();
        session.helperEntities.add(holo.getUniqueId());
    }

    private void openTrailerShutter(DungeonSession session) {
        int cx = session.center.getBlockX();
        int cy = session.center.getBlockY();
        int cz = session.center.getBlockZ();

        // Remove the iron bars at Y+2 and Y+3 in the shutter opening, keep a counter at Y+2 (cx-1, cx+1) or open all 6 blocks
        for (Location loc : session.shutterLocations) {
            // Keep a low counter table at Y+2 on the left/right edges, or open the whole shutter window so players can easily right-click the seller
            setDungeonMaterial(session, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), Material.AIR);
        }
        // Add open iron trapdoors above the window (Y+3, Z+3) to look like a lifted awning shutter
        for (int dx = -1; dx <= 1; dx++) {
            TrapDoor awning = (TrapDoor) Bukkit.createBlockData(Material.IRON_TRAPDOOR);
            awning.setFacing(BlockFace.SOUTH);
            awning.setHalf(Bisected.Half.TOP);
            awning.setOpen(false);
            setDungeonBlock(session, cx + dx, cy + 4, cz + 3, awning);
        }

        updateShutterHologram(
                session,
                Component.text("🔓 Лавка Великого Кузнеца открыта! (ПКМ по Кузнецу)", NamedTextColor.GREEN, TextDecoration.BOLD));
    }

    private void updateShutterHologram(DungeonSession session, Component text) {
        if (session.hologramId == null) return;
        Entity ent = Bukkit.getEntity(session.hologramId);
        if (ent instanceof TextDisplay td && td.isValid()) {
            td.text(text);
        }
    }

    private void cleanupDungeon(DungeonSession session, boolean closeInventories) {
        if (session == null) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.hideBossBar(session.bossBar);
            if (closeInventories && session.merchant != null
                    && player.getOpenInventory().getTopInventory() instanceof org.bukkit.inventory.MerchantInventory mi
                    && session.merchant.equals(mi.getMerchant())) {
                player.closeInventory();
            }
        }

        killAllDungeonMobs(session);

        for (UUID id : new ArrayList<>(session.helperEntities)) {
            Entity ent = Bukkit.getEntity(id);
            if (ent != null) {
                ent.remove();
            }
        }
        session.helperEntities.clear();

        // Недостроенная очередь отбрасывается; уже применённые блоки откатываются пакетами по тикам
        session.building = false;
        session.buildQueue.clear();

        List<Map.Entry<Location, BlockData>> entries = new ArrayList<>(session.originalBlocks.entrySet());
        Collections.reverse(entries);
        RestoreJob job = new RestoreJob(session, entries, new ArrayList<>(session.tileStates.values()));
        session.tileStates.clear();
        session.originalBlocks.clear();
        session.protectedBlockKeys.clear();
        restoreJobs.add(job); // обрабатывается в tickBatchWork() (или синхронно в onDisable)
    }

    // =========================================================================
    // RANDOM REWARD SHOP & CUSTOM ITEMS (48 DIAMONDS TO 32 ENCHANTED GOLDEN APPLES)
    // =========================================================================

    private void generateRandomBlacksmithTrades(DungeonSession session) {
        session.trades.clear();
        session.merchant = null;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        List<MerchantRecipe> pool = new ArrayList<>();

        // 1. Палка с текстурой булавы (Stick with Mace texture)
        pool.add(createTrade(createMaceTexturedStick(rnd), priceInCrapplesOrDiamonds(rnd, 6, 16, true), 2));

        // 2. Лодка с текстурой спавнера (Boat with Spawner texture)
        pool.add(createTrade(createSpawnerTexturedBoat(), priceInCrapplesOrDiamonds(rnd, 4, 12, true), 2));

        // 3. Ядра улучшения (Upgrade Cores — 10%, 15%, 17%, and Master 25%)
        pool.add(createTrade(createUpgradeCore("cb_orange"), priceDiamonds(rnd, 48, 64), 3));
        pool.add(createTrade(createUpgradeCore("cb_purple"), priceCrapples(rnd, 4, 10), 3));
        pool.add(createTrade(createUpgradeCore("cb_green"), priceCrapples(rnd, 8, 18), 2));
        pool.add(createTrade(createUpgradeCore("cb_master"), priceCrapples(rnd, 18, 32), 1));

        // 4. Сбалансированные боевые зелья (PvP: окна 12-45 сек., кулдауны 40-75 сек., компромиссы Kiss/Curse)
        pool.add(createTrade(
                createCustomPotion(Material.POTION, "✦ Зелье Ярости Кузнеца (Сила III, 0:30)", Color.fromRGB(180, 20, 20), 45, null,
                        new PotionEffect(PotionEffectType.STRENGTH, 20 * 30, 2),
                        new PotionEffect(PotionEffectType.SPEED, 20 * 30, 0)),
                priceDiamonds(rnd, 48, 64), 4));
        pool.add(createTrade(
                createCustomPotion(Material.SPLASH_POTION, "✦ Взрывное Зелье Титана (Сила III, 0:18, Свечение)", Color.fromRGB(220, 0, 40), 60, null,
                        new PotionEffect(PotionEffectType.STRENGTH, 20 * 18, 2),
                        new PotionEffect(PotionEffectType.GLOWING, 20 * 18, 0),
                        new PotionEffect(PotionEffectType.SLOWNESS, 20 * 18, 0)),
                priceCrapples(rnd, 5, 9), 3));
        pool.add(createTrade(
                createCustomPotion(Material.POTION, "✦ Зелье Сверхзвука (Скорость III, 0:35)", Color.fromRGB(80, 220, 255), 40, null,
                        new PotionEffect(PotionEffectType.SPEED, 20 * 35, 2),
                        new PotionEffect(PotionEffectType.JUMP_BOOST, 20 * 35, 1)),
                priceDiamonds(rnd, 48, 64), 4));
        pool.add(createTrade(
                createCustomPotion(Material.POTION, "✦ Эликсир Бессмертия (Регенерация III 0:12 + Поглощение III)", Color.fromRGB(255, 105, 180), 60, null,
                        new PotionEffect(PotionEffectType.REGENERATION, 20 * 12, 2),
                        new PotionEffect(PotionEffectType.ABSORPTION, 20 * 45, 2)),
                priceCrapples(rnd, 6, 12), 3));
        pool.add(createTrade(
                createCustomPotion(Material.POTION, "✦ Отвар Наковальни (Сопротивление II, 0:25, Замедление)", Color.fromRGB(110, 110, 140), 60, null,
                        new PotionEffect(PotionEffectType.RESISTANCE, 20 * 25, 1),
                        new PotionEffect(PotionEffectType.SLOWNESS, 20 * 25, 1),
                        new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 20 * 120, 0)),
                priceCrapples(rnd, 8, 14), 2));
        pool.add(createTrade(
                createCustomPotion(Material.SPLASH_POTION, "✦ Проклятие Пустыря (Слабость II + Замедление II + Тьма)", Color.fromRGB(60, 40, 70), 45, null,
                        new PotionEffect(PotionEffectType.WEAKNESS, 20 * 15, 1),
                        new PotionEffect(PotionEffectType.SLOWNESS, 20 * 12, 1),
                        new PotionEffect(PotionEffectType.DARKNESS, 20 * 6, 0)),
                priceDiamonds(rnd, 52, 64), 3));
        pool.add(createTrade(
                createCustomPotion(Material.POTION, "✦ Коктейль Великого Кузнеца (Сила II + Скорость II + Реген II, 0:45)", Color.fromRGB(255, 175, 0), 75, null,
                        new PotionEffect(PotionEffectType.STRENGTH, 20 * 45, 1),
                        new PotionEffect(PotionEffectType.SPEED, 20 * 45, 1),
                        new PotionEffect(PotionEffectType.REGENERATION, 20 * 20, 1),
                        new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 20 * 120, 0)),
                priceCrapples(rnd, 12, 20), 2));
        pool.add(createTrade(createMoltenSlagPotion(), priceCrapples(rnd, 4, 10), 3));

        // 4b. Новые тактические предметы
        pool.add(createTrade(createSlagGrapple(), priceCrapples(rnd, 8, 16), 2));
        pool.add(createTrade(createAnvilTotem(), priceCrapples(rnd, 12, 24), 1));

        // 5. Расходники для ПВП (PvP Consumables)
        pool.add(createTrade(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, rnd.nextInt(2, 5)), priceDiamonds(rnd, 56, 64), 4));
        pool.add(createTrade(new ItemStack(Material.GOLDEN_APPLE, rnd.nextInt(12, 25)), priceDiamonds(rnd, 48, 64), 5));
        pool.add(createTrade(new ItemStack(Material.TOTEM_OF_UNDYING, 1), priceDiamonds(rnd, 48, 64), 5));
        pool.add(createTrade(new ItemStack(Material.WIND_CHARGE, 64), priceDiamonds(rnd, 48, 56), 5));
        pool.add(createTrade(new ItemStack(Material.ENDER_PEARL, 16), priceDiamonds(rnd, 48, 56), 5));
        pool.add(createTrade(new ItemStack(Material.COBWEB, 32), priceDiamonds(rnd, 48, 56), 5));
        pool.add(createTrade(new ItemStack(Material.END_CRYSTAL, 8), priceDiamonds(rnd, 52, 64), 4));
        pool.add(createTrade(new ItemStack(Material.RESPAWN_ANCHOR, 4), priceDiamonds(rnd, 48, 60), 4));
        pool.add(createTrade(new ItemStack(Material.EXPERIENCE_BOTTLE, 64), priceDiamonds(rnd, 48, 58), 5));

        // 6. И многое другое (Over-enchanted Blacksmith gear, books, rare materials, unique weapons)
        pool.add(createTrade(createBlacksmithBlade(), priceCrapples(rnd, 20, 32), 1));
        pool.add(createTrade(createBlacksmithChestplate(), priceCrapples(rnd, 22, 32), 1));
        pool.add(createTrade(createBlacksmithHelmet(), priceCrapples(rnd, 18, 28), 1));
        pool.add(createTrade(createBlacksmithBoots(), priceCrapples(rnd, 18, 28), 1));
        pool.add(createTrade(createForgeWarhammer(), priceCrapples(rnd, 20, 32), 1));
        pool.add(createTrade(createVulcanShield(), priceCrapples(rnd, 16, 28), 1));
        pool.add(createTrade(createTitanDraught(), priceCrapples(rnd, 6, 12), 3));
        pool.add(createTrade(createSlagCrossbow(), priceCrapples(rnd, 14, 26), 1));

        List<MerchantRecipe> overBooks = new ArrayList<>();
        overBooks.add(createTrade(createOverleveledBook(Enchantment.SHARPNESS, 6, "Книга Кузнеца: Острота VI"), priceCrapples(rnd, 14, 28), 2));
        overBooks.add(createTrade(createOverleveledBook(Enchantment.PROTECTION, 5, "Книга Кузнеца: Защита V"), priceCrapples(rnd, 14, 28), 2));
        overBooks.add(createTrade(createOverleveledBook(Enchantment.UNBREAKING, 4, "Книга Кузнеца: Прочность IV"), priceCrapples(rnd, 10, 20), 2));
        overBooks.add(createTrade(createOverleveledBook(Enchantment.EFFICIENCY, 6, "Книга Кузнеца: Эффективность VI"), priceCrapples(rnd, 10, 20), 2));
        overBooks.add(createTrade(createOverleveledBook(Enchantment.POWER, 6, "Книга Кузнеца: Сила VI"), priceCrapples(rnd, 12, 22), 2));
        overBooks.add(createTrade(createOverleveledBook(Enchantment.FEATHER_FALLING, 5, "Книга Кузнеца: Невесомость V"), priceCrapples(rnd, 10, 18), 2));
        overBooks.add(createTrade(createOverleveledBook(Enchantment.FIRE_ASPECT, 3, "Книга Кузнеца: Заговор Огня III"), priceCrapples(rnd, 10, 18), 2));
        overBooks.add(createTrade(createOverleveledBook(Enchantment.LOOTING, 4, "Книга Кузнеца: Добыча IV"), priceCrapples(rnd, 12, 24), 2));
        overBooks.add(createTrade(createOverleveledBook(Enchantment.FORTUNE, 4, "Книга Кузнеца: Удача IV"), priceCrapples(rnd, 12, 24), 2));
        overBooks.add(createTrade(createOverleveledBook(Enchantment.SWEEPING_EDGE, 4, "Книга Кузнеца: Разящий Клинок IV"), priceCrapples(rnd, 8, 16), 2));
        overBooks.add(createTrade(createOverleveledBook(Enchantment.THORNS, 4, "Книга Кузнеца: Шипы IV"), priceCrapples(rnd, 8, 16), 2));
        overBooks.add(createTrade(createOverleveledBook(Enchantment.KNOCKBACK, 3, "Книга Кузнеца: Отдача III"), priceCrapples(rnd, 8, 14), 2));
        overBooks.add(createTrade(createOverleveledBook(Enchantment.PUNCH, 3, "Книга Кузнеца: Опрокидывание III"), priceCrapples(rnd, 8, 14), 2));
        overBooks.add(createTrade(createOverleveledBook(Enchantment.RESPIRATION, 4, "Книга Кузнеца: Подводное Дыхание IV"), priceCrapples(rnd, 8, 14), 2));
        overBooks.add(createTrade(createOverleveledBook(Enchantment.AQUA_AFFINITY, 2, "Книга Кузнеца: Подводник II"), priceCrapples(rnd, 8, 14), 2));
        overBooks.add(createTrade(createOverleveledBook(Enchantment.QUICK_CHARGE, 4, "Книга Кузнеца: Быстрая Перезарядка IV"), priceCrapples(rnd, 10, 18), 2));
        overBooks.add(createTrade(createOverleveledBook(Enchantment.PIERCING, 5, "Книга Кузнеца: Пронзающая Стрела V"), priceCrapples(rnd, 10, 18), 2));
        pool.addAll(overBooks);
        pool.add(createTrade(new ItemStack(Material.NETHERITE_INGOT, rnd.nextInt(2, 5)), priceCrapples(rnd, 6, 14), 3));
        pool.add(createTrade(new ItemStack(Material.NETHER_STAR, 1), priceCrapples(rnd, 8, 16), 2));

        // Always include the signature items (Mace-textured Stick, Spawner-textured Boat, at least 2 Upgrade Cores,
        // at least 2 over-leveled books, and PvP consumables) + shuffle the rest for random assortment!
        List<MerchantRecipe> guaranteed = new ArrayList<>();
        guaranteed.add(pool.get(0)); // Палка с текстурой булавы
        guaranteed.add(pool.get(1)); // Лодка с текстурой спавнера
        int coreA = 2 + rnd.nextInt(4);
        int coreB = 2 + rnd.nextInt(3);
        if (coreB >= coreA) coreB++; // два РАЗНЫХ ядра (раньше могло выпасть дубликатом)
        guaranteed.add(pool.get(coreA)); // Ядро улучшения #1
        guaranteed.add(pool.get(coreB)); // Ядро улучшения #2
        guaranteed.add(overBooks.get(rnd.nextInt(overBooks.size()))); // овер-лимитная книга #1
        guaranteed.add(overBooks.get(rnd.nextInt(overBooks.size()))); // овер-лимитная книга #2

        List<MerchantRecipe> remaining = new ArrayList<>(pool.subList(2, pool.size()));
        Collections.shuffle(remaining, rnd);

        session.trades.addAll(guaranteed);
        for (MerchantRecipe r : remaining) {
            if (session.trades.size() >= 18) break;
            if (!session.trades.contains(r)) {
                session.trades.add(r);
            }
        }
        Collections.shuffle(session.trades, rnd);

        // Проверка каждого трейда (количество, уровни чар, сериализация) — битые лоты не попадают в лавку
        for (String problem : validateTrades(session.trades)) {
            getLogger().warning("Трейд Житака отброшен: " + problem);
        }

        if (session.sellerId != null && Bukkit.getEntity(session.sellerId) instanceof Villager v) {
            v.setRecipes(session.trades);
        }
    }

    /** Удаляет из списка невалидные трейды и возвращает описание проблем. */
    private List<String> validateTrades(List<MerchantRecipe> trades) {
        List<String> problems = new ArrayList<>();
        trades.removeIf(recipe -> {
            String error = validateRecipe(recipe);
            if (error != null) {
                problems.add(error);
                return true;
            }
            return false;
        });
        return problems;
    }

    /** @return null если трейд корректен и безопасен для клиента 1.21.x, иначе причина */
    private String validateRecipe(MerchantRecipe recipe) {
        ItemStack result = recipe.getResult();
        String label = result.getType().name();
        if (result.getType().isAir()) return "пустой результат";
        if (result.getAmount() < 1 || result.getAmount() > result.getMaxStackSize()) {
            return label + ": количество результата " + result.getAmount() + " вне 1.." + result.getMaxStackSize();
        }
        if (recipe.getMaxUses() < 1) return label + ": maxUses < 1";
        if (recipe.getIngredients().isEmpty() || recipe.getIngredients().size() > 2) {
            return label + ": нужно 1-2 ингредиента";
        }
        for (ItemStack ingredient : recipe.getIngredients()) {
            if (ingredient == null || ingredient.getType().isAir()) return label + ": пустой ингредиент";
            int limit = Math.min(ingredient.getMaxStackSize(), 99);
            if (ingredient.getAmount() < 1 || ingredient.getAmount() > limit) {
                return label + ": цена " + ingredient.getType().name() + " x" + ingredient.getAmount() + " вне 1.." + limit;
            }
        }
        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            Map<Enchantment, Integer> all = new HashMap<>(meta.getEnchants());
            if (meta instanceof EnchantmentStorageMeta esm) all.putAll(esm.getStoredEnchants());
            for (Map.Entry<Enchantment, Integer> e : all.entrySet()) {
                // Компонент enchantments в 1.21 допускает уровни 1..255
                if (e.getValue() < 1 || e.getValue() > 255) {
                    return label + ": уровень " + e.getKey().getKey().getKey() + "=" + e.getValue() + " вне 1..255";
                }
            }
        }
        try {
            ItemStack back = ItemStack.deserializeBytes(result.serializeAsBytes());
            if (!back.isSimilar(result)) return label + ": предмет изменился после сериализации";
        } catch (Throwable t) {
            return label + ": ошибка сериализации (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")";
        }
        return null;
    }

    private ItemStack priceDiamonds(ThreadLocalRandom rnd, int min48, int max64) {
        int count = rnd.nextInt(Math.max(48, min48), Math.min(64, max64) + 1);
        return new ItemStack(Material.DIAMOND, count);
    }

    private ItemStack priceCrapples(ThreadLocalRandom rnd, int min1, int max32) {
        int count = rnd.nextInt(Math.max(1, min1), Math.min(32, max32) + 1);
        return new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, count);
    }

    private ItemStack priceInCrapplesOrDiamonds(ThreadLocalRandom rnd, int minCrapples, int maxCrapples, boolean allowDiamonds) {
        if (allowDiamonds && rnd.nextBoolean()) {
            return priceDiamonds(rnd, 48, 64);
        }
        return priceCrapples(rnd, minCrapples, maxCrapples);
    }

    private MerchantRecipe createTrade(ItemStack result, ItemStack cost, int maxUses) {
        MerchantRecipe recipe = new MerchantRecipe(result, 0, maxUses, false, 0, 1.0F);
        recipe.addIngredient(cost);
        return recipe;
    }

    @SuppressWarnings("deprecation")
    private void applyModel(ItemMeta meta, NamespacedKey itemModel, int fallbackCmd) {
        try {
            meta.setItemModel(itemModel);
        } catch (Throwable ignored) {
        }
        try {
            meta.setCustomModelData(fallbackCmd);
            CustomModelDataComponent comp = meta.getCustomModelDataComponent();
            comp.setFloats(List.of((float) fallbackCmd));
            meta.setCustomModelDataComponent(comp);
        } catch (Throwable ignored) {
        }
    }

    private ItemStack createMaceTexturedStick(ThreadLocalRandom rnd) {
        ItemStack item = new ItemStack(Material.STICK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            applyModel(meta, NamespacedKey.minecraft("mace"), 40001);
            meta.displayName(mm.deserialize("<gradient:#f7971e:#ffd200><bold>✦ Палка-Булава Великого Кузнеца ✦</bold></gradient>")
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Редкий сувенир из кузни на пустыре.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Обычная палка, выкованная в форме булавы!", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)
            ));
            meta.addEnchant(Enchantment.KNOCKBACK, rnd.nextInt(2, 5), true);
            meta.addEnchant(Enchantment.SHARPNESS, rnd.nextInt(3, 6), true);
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(specialItemKey, PersistentDataType.STRING, "mace_stick");
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createSpawnerTexturedBoat() {
        ItemStack item = new ItemStack(Material.OAK_BOAT);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            applyModel(meta, NamespacedKey.minecraft("spawner"), 40002);
            meta.displayName(mm.deserialize("<gradient:#00d2ff:#3a7bd5><bold>✦ Лодка-Спавнер Великого Кузнеца ✦</bold></gradient>")
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Аномальная лодка с текстурой спавнера монстров.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Эксклюзив из трейлера Великого Кузнеца.", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false)
            ));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(specialItemKey, PersistentDataType.STRING, "spawner_boat");
            item.setItemMeta(meta);
        }
        return item;
    }

    public ItemStack createUpgradeCore(String coreId) {
        Material mat;
        int cmd;
        String title;
        String desc;
        String chanceLine;

        switch (coreId) {
            case "cb_orange" -> {
                mat = Material.COMMAND_BLOCK;
                cmd = 30001;
                title = "<gradient:#FF8008:#FFC837><bold>✴ Импульсное Ядро Древних</bold></gradient>";
                desc = "Сгусток первозданной энергии, пульсирующий в такт сердцу мира.";
                chanceLine = "10% шанс повысить случайное зачарование на уровень.";
            }
            case "cb_purple" -> {
                mat = Material.REPEATING_COMMAND_BLOCK;
                cmd = 30002;
                title = "<gradient:#8E2DE2:#4A00E0><bold>۞ Ядро Вечного Цикла</bold></gradient>";
                desc = "Бесконечный вихрь хроно-материи, способный искажать реальность.";
                chanceLine = "15% шанс повысить случайное зачарование на уровень.";
            }
            case "cb_green" -> {
                mat = Material.CHAIN_COMMAND_BLOCK;
                cmd = 30003;
                title = "<gradient:#11998e:#38ef7d><bold>❖ Цепное Ядро Сингулярности</bold></gradient>";
                desc = "Холодный кристалл абсолютного порядка, связывающий стихии воедино.";
                chanceLine = "17% шанс повысить случайное зачарование на уровень.";
            }
            default -> {
                mat = Material.HEAVY_CORE;
                cmd = 30004;
                title = "<gradient:#f12711:#f5af19><bold>✦ Кузнечное Ядро Мастера</bold></gradient>";
                desc = "Раскалённое ядро из горна самого Великого Кузнеца.";
                chanceLine = "25% шанс повысить случайное зачарование на уровень.";
            }
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            try {
                CustomModelDataComponent comp = meta.getCustomModelDataComponent();
                comp.setFloats(List.of((float) cmd));
                meta.setCustomModelDataComponent(comp);
            } catch (Throwable ignored) {
            }
            meta.displayName(mm.deserialize(title).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("✦ ЛЕГЕНДАРНЫЙ АРТЕФАКТ ✦", NamedTextColor.LIGHT_PURPLE)
                    .decorate(TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(Component.text(desc, NamedTextColor.DARK_PURPLE).decoration(TextDecoration.ITALIC, true));
            lore.add(Component.empty());
            lore.add(Component.text("ПРИМЕНЕНИЕ", NamedTextColor.LIGHT_PURPLE)
                    .decorate(TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Рецепты легендарных артефактов; наковальня:", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text(chanceLine, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("При неудаче теряется 80% оставшейся прочности.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            if ("cb_master".equals(coreId)) {
                lore.add(Component.text("Кузнечная страховка: первая неудача на предмете", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("не отнимает прочность и не ломает его (одноразово).", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
            meta.setEnchantmentGlintOverride(true);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);

            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(coreIdKey, PersistentDataType.STRING, coreId);
            // Also stamp FokendLegendaryWepons keys so both plugins seamlessly recognize the cores!
            pdc.set(new NamespacedKey("fokendlegendarywepons", "legendary_id"), PersistentDataType.STRING, coreId);
            pdc.set(new NamespacedKey("fokendlegendarywepons", "weapon_id"), PersistentDataType.STRING, coreId);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createCustomPotion(Material bottleType, String name, Color color, int cooldownSeconds,
                                         String specialId, PotionEffect... effects) {
        ItemStack item = new ItemStack(bottleType);
        if (item.getItemMeta() instanceof PotionMeta pm) {
            pm.displayName(Component.text(name, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            pm.setColor(color);
            for (PotionEffect effect : effects) {
                pm.addCustomEffect(effect, true);
            }
            pm.lore(List.of(Component.text("Боевой эликсир Житака. Перезарядка: " + cooldownSeconds + " сек.",
                    NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            pm.getPersistentDataContainer().set(new NamespacedKey(this, "potion_cd_seconds"),
                    PersistentDataType.INTEGER, cooldownSeconds);
            if (specialId != null) {
                pm.getPersistentDataContainer().set(specialItemKey, PersistentDataType.STRING, specialId);
            }
            item.setItemMeta(pm);
        }
        return item;
    }

    private ItemStack createMoltenSlagPotion() {
        ItemStack item = createCustomPotion(Material.SPLASH_POTION, "✦ Эссенция Жидкого Шлака", Color.fromRGB(255, 69, 0),
                35, "molten_slag_potion", new PotionEffect(PotionEffectType.GLOWING, 20 * 3, 0));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            applyModel(meta, NamespacedKey.minecraft("splash_potion"), 40004);
            meta.lore(List.of(
                    Component.text("✦ АЛХИМИЯ ВЕЛИКОГО КУЗНЕЦА ✦", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                    Component.text("«Шлаковый ожог» (10 сек.): исцеление и Поглощение -50%.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Перезарядка: 35 сек.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createSlagGrapple() {
        ItemStack item = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            applyModel(meta, NamespacedKey.minecraft("trial_key"), 40003);
            meta.displayName(mm.deserialize("<gradient:#ff8008:#ffc837><bold>✦ Крюк-Кошка Шлакосборщика ✦</bold></gradient>")
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Гарпун [в блок]: стремительный рывок к точке зацепа (Без кулдауна).", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Стяжка [в игрока]: притягивает и блокирует Жемчуг/Ветер на 3 сек (Без кулдауна).", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            meta.setUnbreakable(true);
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(specialItemKey, PersistentDataType.STRING, "slag_grapple");
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createAnvilTotem() {
        ItemStack item = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            applyModel(meta, NamespacedKey.minecraft("anvil"), 40005);
            meta.displayName(mm.deserialize("<gradient:#f12711:#f5af19><bold>✦ Печать Раскалённой Наковальни ✦</bold></gradient>")
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("При смертельном уроне: отбрасывает врагов в радиусе 5 блоков,", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("наносит 10 урона и чинит броню на +15%. Перезарядка: 90 сек.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(specialItemKey, PersistentDataType.STRING, "anvil_totem");
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createBlacksmithBlade() {
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = sword.getItemMeta();
        if (meta != null) {
            meta.displayName(mm.deserialize("<gradient:#ff512f:#dd2476><bold>⚔ Клинок Великого Кузнеца</bold></gradient>")
                    .decoration(TextDecoration.ITALIC, false));
            meta.addEnchant(Enchantment.SHARPNESS, 6, true);
            meta.addEnchant(Enchantment.UNBREAKING, 4, true);
            meta.addEnchant(Enchantment.FIRE_ASPECT, 3, true);
            meta.addEnchant(Enchantment.LOOTING, 4, true);
            sword.setItemMeta(meta);
        }
        return sword;
    }

    private ItemStack createBlacksmithChestplate() {
        ItemStack chest = new ItemStack(Material.NETHERITE_CHESTPLATE);
        ItemMeta meta = chest.getItemMeta();
        if (meta != null) {
            meta.displayName(mm.deserialize("<gradient:#f7971e:#ffd200><bold>🛡 Кираса Великого Кузнеца</bold></gradient>")
                    .decoration(TextDecoration.ITALIC, false));
            meta.addEnchant(Enchantment.PROTECTION, 5, true);
            meta.addEnchant(Enchantment.UNBREAKING, 4, true);
            meta.addEnchant(Enchantment.MENDING, 1, true);
            chest.setItemMeta(meta);
        }
        return chest;
    }

    private ItemStack createForgeWarhammer() {
        ItemStack item = new ItemStack(Material.MACE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(mm.deserialize("<gradient:#ff4500:#ff8c00><bold>⚒ Кузнечный Молот Вулкана ✦</bold></gradient>")
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Тяжелейший молот, выкованный в жерле подземной кузницы.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Удар: осыпает жертву искрами лавы и поджигает на 5 сек.", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                    Component.text("ПКМ [по земле]: сокрушительный удар в радиусе 6 блоков!", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                    Component.text("  Наносит 12 урона, подбрасывает в воздух и накладывает", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("  Замедление III и Слабость II на 4 сек (КД 12 сек).", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
            meta.setUnbreakable(true);
            meta.setEnchantmentGlintOverride(true);
            meta.addEnchant(Enchantment.SHARPNESS, 6, true);
            meta.addEnchant(Enchantment.UNBREAKING, 5, true);
            meta.addEnchant(Enchantment.FIRE_ASPECT, 3, true);
            meta.addEnchant(Enchantment.KNOCKBACK, 3, true);
            meta.getPersistentDataContainer().set(specialItemKey, PersistentDataType.STRING, "forge_warhammer");
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createVulcanShield() {
        ItemStack item = new ItemStack(Material.SHIELD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(mm.deserialize("<gradient:#e65c00:#f9d423><bold>🛡 Бастион Гефеста ✦</bold></gradient>")
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Выкован из огнеупорной вулканической стали.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Блок удара: отражает 40% урона обратно атакующему,", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                    Component.text("выбрасывает облако перегретого пара, ослепляя", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                    Component.text("и поджигая нападающего на 4 секунды.", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)));
            meta.setUnbreakable(true);
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(specialItemKey, PersistentDataType.STRING, "vulcan_shield");
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createTitanDraught() {
        ItemStack item = new ItemStack(Material.POTION);
        org.bukkit.inventory.meta.PotionMeta pm = (org.bukkit.inventory.meta.PotionMeta) item.getItemMeta();
        if (pm != null) {
            pm.displayName(mm.deserialize("<gradient:#da22ff:#9733ee><bold>✦ Эликсир Титана ✦</bold></gradient>")
                    .decoration(TextDecoration.ITALIC, false));
            pm.setColor(Color.fromRGB(150, 0, 220));
            pm.lore(List.of(
                    Component.text("Тайное варево великих кузнецов.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Эффект при употреблении (25 сек.):", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                    Component.text("  + Поглощение III (+6 золотых сердец)", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                    Component.text("  + Сопротивление II", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                    Component.text("  + Спешка II", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                    Component.text("Перезарядка: 45 сек.", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)));
            pm.setEnchantmentGlintOverride(true);
            pm.getPersistentDataContainer().set(specialItemKey, PersistentDataType.STRING, "titan_draught");
            item.setItemMeta(pm);
        }
        return item;
    }

    private ItemStack createSlagCrossbow() {
        ItemStack item = new ItemStack(Material.CROSSBOW);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(mm.deserialize("<gradient:#ff0844:#ffb199><bold>✦ Шлаковый Огнемёт Кузнеца ✦</bold></gradient>")
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Модифицированный кузнечный арбалет под жидкий шлак.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Стрелы детонируют при попадании, нанося 9 урона", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                    Component.text("в радиусе 4 блоков и поджигая врагов.", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)));
            meta.setUnbreakable(true);
            meta.addEnchant(Enchantment.QUICK_CHARGE, 4, true);
            meta.addEnchant(Enchantment.PIERCING, 4, true);
            meta.addEnchant(Enchantment.UNBREAKING, 4, true);
            meta.getPersistentDataContainer().set(specialItemKey, PersistentDataType.STRING, "slag_crossbow");
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createBlacksmithHelmet() {
        ItemStack helmet = new ItemStack(Material.NETHERITE_HELMET);
        ItemMeta meta = helmet.getItemMeta();
        if (meta != null) {
            meta.displayName(mm.deserialize("<gradient:#f7971e:#ffd200><bold>👑 Венец Великого Кузнеца</bold></gradient>")
                    .decoration(TextDecoration.ITALIC, false));
            meta.addEnchant(Enchantment.PROTECTION, 5, true);
            meta.addEnchant(Enchantment.RESPIRATION, 4, true);
            meta.addEnchant(Enchantment.AQUA_AFFINITY, 2, true);
            meta.addEnchant(Enchantment.UNBREAKING, 4, true);
            meta.addEnchant(Enchantment.MENDING, 1, true);
            helmet.setItemMeta(meta);
        }
        return helmet;
    }

    private ItemStack createBlacksmithBoots() {
        ItemStack boots = new ItemStack(Material.NETHERITE_BOOTS);
        ItemMeta meta = boots.getItemMeta();
        if (meta != null) {
            meta.displayName(mm.deserialize("<gradient:#f7971e:#ffd200><bold>👢 Сапоги Великого Кузнеца</bold></gradient>")
                    .decoration(TextDecoration.ITALIC, false));
            meta.addEnchant(Enchantment.PROTECTION, 5, true);
            meta.addEnchant(Enchantment.FEATHER_FALLING, 5, true);
            meta.addEnchant(Enchantment.DEPTH_STRIDER, 3, true);
            meta.addEnchant(Enchantment.UNBREAKING, 4, true);
            meta.addEnchant(Enchantment.MENDING, 1, true);
            boots.setItemMeta(meta);
        }
        return boots;
    }

    private ItemStack createOverleveledBook(Enchantment enchant, int level, String title) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        if (book.getItemMeta() instanceof EnchantmentStorageMeta esm) {
            esm.displayName(Component.text("✦ " + title, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            esm.addStoredEnchant(enchant, level, true);
            book.setItemMeta(esm);
        }
        return book;
    }

    // =========================================================================
    // EVENT LISTENERS: WEAPON RESTRICTIONS, MOBS, PROTECTION, SELLER, CORES
    // =========================================================================

    private boolean isFrostSpear(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        for (NamespacedKey key : pdc.getKeys()) {
            if (pdc.has(key, PersistentDataType.STRING)) {
                String val = pdc.get(key, PersistentDataType.STRING);
                if ("ice_spear".equalsIgnoreCase(val)) return true;
            }
        }
        try {
            if (meta.hasCustomModelDataComponent()) {
                List<Float> floats = meta.getCustomModelDataComponent().getFloats();
                if (floats.contains(20002.0F)) return true;
            }
        } catch (Throwable ignored) {
        }
        if (meta.hasDisplayName() && meta.displayName() != null) {
            String plain = PlainTextComponentSerializer.plainText().serialize(meta.displayName()).toLowerCase(Locale.ROOT);
            return plain.contains("морозное копь") || plain.contains("ледяное копь");
        }
        return false;
    }

    private boolean isBlockedMaceOrSpear(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        if (isFrostSpear(item)) return false;

        Material type = item.getType();
        String name = type.name();
        if (name.equals("MACE") || name.endsWith("_SPEAR") || type == Material.TRIDENT) {
            return true;
        }

        if (item.hasItemMeta() && item.getItemMeta() != null) {
            ItemMeta meta = item.getItemMeta();
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            if ("mace_stick".equals(pdc.get(specialItemKey, PersistentDataType.STRING))) {
                return false; // Палка с текстурой булавы — это палка, а не булава
            }
            for (NamespacedKey key : pdc.getKeys()) {
                if (pdc.has(key, PersistentDataType.STRING)) {
                    String val = pdc.get(key, PersistentDataType.STRING);
                    if (val != null && (val.contains("mace") || val.contains("spear"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWastelandWeaponDamage(EntityDamageByEntityEvent event) {
        if (activeDungeon == null) return;
        boolean entityInWasteland = isInsideWasteland(activeDungeon, event.getEntity().getLocation());
        boolean damagerInWasteland = isInsideWasteland(activeDungeon, event.getDamager().getLocation());
        if (!entityInWasteland && !damagerInWasteland) return;

        if (event.getDamager() instanceof Player attacker) {
            ItemStack weapon = attacker.getInventory().getItemInMainHand();
            if (isBlockedMaceOrSpear(weapon)) {
                event.setCancelled(true);
                attacker.sendActionBar(Component.text(
                        "⚠ На пустыре не работают булавы и копья (кроме Морозного копья)!",
                        NamedTextColor.RED));
                return;
            }
        } else if (event.getDamager() instanceof Trident trident) {
            ItemStack item = trident.getItemStack();
            if (!isFrostSpear(item)) {
                event.setCancelled(true);
                if (trident.getShooter() instanceof Player shooter) {
                    shooter.sendActionBar(Component.text(
                            "⚠ На пустыре не работают булавы и копья (кроме Морозного копья)!",
                            NamedTextColor.RED));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWastelandWeaponInteract(PlayerInteractEvent event) {
        if (activeDungeon == null) return;
        Player player = event.getPlayer();
        if (!isInsideWasteland(activeDungeon, player.getLocation())) return;

        ItemStack held = event.getItem();
        if (isBlockedMaceOrSpear(held)) {
            event.setCancelled(true);
            player.sendActionBar(Component.text(
                    "⚠ На пустыре не работают булавы и копья (кроме Морозного копья)!",
                    NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWastelandProjectileLaunch(ProjectileLaunchEvent event) {
        if (activeDungeon == null) return;
        Projectile proj = event.getEntity();
        if (!(proj.getShooter() instanceof Player player)) return;
        if (!isInsideWasteland(activeDungeon, player.getLocation())
                && !isInsideWasteland(activeDungeon, proj.getLocation())) return;

        if (proj instanceof Trident trident && !isFrostSpear(trident.getItemStack())) {
            event.setCancelled(true);
            player.sendActionBar(Component.text(
                    "⚠ На пустыре не работают булавы и копья (кроме Морозного копья)!",
                    NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDungeonMobDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.getPersistentDataContainer().has(dungeonMobKey, PersistentDataType.BYTE)) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            if (activeDungeon != null) {
                activeDungeon.spawnedMobs.remove(entity.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDungeonMobCombust(EntityCombustEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(dungeonMobKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDungeonMobTarget(EntityTargetLivingEntityEvent event) {
        if (!event.getEntity().getPersistentDataContainer().has(dungeonMobKey, PersistentDataType.BYTE)) return;
        LivingEntity target = event.getTarget();
        if (target == null) return;
        // Prevent dungeon mobs from fighting each other or attacking the Blacksmith Seller
        if (target.getPersistentDataContainer().has(dungeonMobKey, PersistentDataType.BYTE)
                || target.getPersistentDataContainer().has(sellerKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
            if (activeDungeon != null && event.getEntity() instanceof Mob mob) {
                Player participant = pickRandomParticipant(activeDungeon, ThreadLocalRandom.current());
                if (participant != null) {
                    event.setTarget(participant);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSellerDamage(EntityDamageEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(sellerKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSellerInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Entity clicked = event.getRightClicked();
        if (!clicked.getPersistentDataContainer().has(sellerKey, PersistentDataType.BYTE)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (activeDungeon == null || activeDungeon.state != DungeonState.REWARD) {
            player.sendMessage(Component.text(
                    "🔒 Великий Кузнец откроет лавку только после отражения всех волн!",
                    NamedTextColor.RED));
            return;
        }
        openBlacksmithShop(player, activeDungeon);
    }

    @SuppressWarnings("deprecation")
    private void openBlacksmithShop(Player player, DungeonSession session) {
        // Один Merchant на сессию: счётчики uses/maxUses общие и не сбрасываются повторным открытием
        if (session.merchant == null) {
            session.merchant = Bukkit.createMerchant(
                    Component.text("✦ Лавка Житака — Великого Кузнеца ✦", NamedTextColor.GOLD, TextDecoration.BOLD));
            session.merchant.setRecipes(session.trades);
        }
        player.openMerchant(session.merchant, true);
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7F, 1.2F);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (activeDungeon != null
                && (activeDungeon.state == DungeonState.WAVES || activeDungeon.state == DungeonState.COUNTDOWN)) {
            activeDungeon.participants.remove(event.getPlayer().getUniqueId());
            Bukkit.getScheduler().runTask(this, () -> {
                if (activeDungeon != null && (activeDungeon.state == DungeonState.WAVES || activeDungeon.state == DungeonState.COUNTDOWN)) {
                    List<Player> remaining = getValidPlayersOnWasteland(activeDungeon);
                    if (remaining.isEmpty()) {
                        triggerFailure(activeDungeon, "в зоне данжа не осталось игроков");
                    }
                }
            });
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (activeDungeon != null
                && (activeDungeon.state == DungeonState.WAVES || activeDungeon.state == DungeonState.COUNTDOWN)) {
            Player dead = event.getEntity();
            if (activeDungeon.participants.contains(dead.getUniqueId()) || isInsideWasteland(activeDungeon, dead.getLocation())) {
                Bukkit.getScheduler().runTask(this, () -> {
                    if (activeDungeon != null && (activeDungeon.state == DungeonState.WAVES || activeDungeon.state == DungeonState.COUNTDOWN)) {
                        List<Player> remaining = getValidPlayersOnWasteland(activeDungeon);
                        if (remaining.isEmpty()) {
                            triggerFailure(activeDungeon, "в зоне данжа не осталось игроков");
                        } else {
                            for (Player survivor : remaining) {
                                survivor.sendMessage(Component.text("☠ Игрок " + dead.getName() + " погиб! В зоне осталось бойцов: " + remaining.size(), NamedTextColor.YELLOW));
                            }
                        }
                    }
                });
            }
        }
    }

    // Protect Wasteland & Trailer blocks while active
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (activeDungeon != null && isProtectedDungeonBlock(activeDungeon, event.getBlock())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text("⚠ Нельзя ломать блоки на пустыре Великого Кузнеца!", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (activeDungeon != null && (isProtectedDungeonBlock(activeDungeon, event.getBlock())
                || isInsideWasteland(activeDungeon, event.getBlock().getLocation()))) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text("⚠ Нельзя ставить блоки на пустыре Великого Кузнеца!", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (activeDungeon == null) return;
        event.blockList().removeIf(b -> isProtectedDungeonBlock(activeDungeon, b)
                || isInsideWasteland(activeDungeon, b.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (activeDungeon == null) return;
        event.blockList().removeIf(b -> isProtectedDungeonBlock(activeDungeon, b)
                || isInsideWasteland(activeDungeon, b.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        if (activeDungeon != null && (isProtectedDungeonBlock(activeDungeon, event.getToBlock())
                || isInsideWasteland(activeDungeon, event.getToBlock().getLocation()))) {
            event.setCancelled(true);
        }
    }

    private boolean isProtectedDungeonBlock(DungeonSession session, Block block) {
        return session.protectedBlockKeys.contains(blockKey(block));
    }

    private String blockKey(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    // =========================================================================
    // COMMANDS (/blacksmith <spawn|here|stop|status|shop>)
    // =========================================================================

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            if (activeDungeon != null) {
                Location c = activeDungeon.center;
                sender.sendMessage(Component.text(
                        "⚔ Активный дандж Великого Кузнеца: X=" + c.getBlockX() + ", Y=" + c.getBlockY() + ", Z=" + c.getBlockZ()
                                + " | Статус: " + (activeDungeon.building
                                        ? "BUILDING (осталось блоков: " + activeDungeon.buildQueue.size() + ")"
                                        : activeDungeon.state)
                                + " | Волна: " + activeDungeon.currentWave + "/" + activeDungeon.totalWaves,
                        NamedTextColor.GOLD));
            } else {
                long remSec = Math.max(0L, (nextScheduledSpawnAt - System.currentTimeMillis()) / 1000L);
                sender.sendMessage(Component.text(
                        "⚔ Сейчас активного данджа нет. До следующего спавна: " + (remSec / 60L) + " мин. " + (remSec % 60L) + " сек.",
                        NamedTextColor.YELLOW));
            }
            return true;
        }

        if (!sender.hasPermission("greatblacksmith.admin") && !sender.isOp()) {
            sender.sendMessage(Component.text("У вас нет прав для этой команды!", NamedTextColor.RED));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "spawn" -> {
                nextScheduledSpawnAt = System.currentTimeMillis() + SPAWN_INTERVAL_MILLIS;
                boolean started = beginDungeonSearch(ok -> sender.sendMessage(ok
                        ? Component.text("Место найдено, арена строится по тикам (прогресс: /blacksmith status).", NamedTextColor.GREEN)
                        : Component.text("Не удалось найти или загрузить место для спавна.", NamedTextColor.RED)));
                sender.sendMessage(started
                        ? Component.text("Асинхронный поиск места запущен...", NamedTextColor.YELLOW)
                        : Component.text("Поиск места уже выполняется, подождите.", NamedTextColor.RED));
                return true;
            }
            case "here" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Команда только для игроков!", NamedTextColor.RED));
                    return true;
                }
                nextScheduledSpawnAt = System.currentTimeMillis() + SPAWN_INTERVAL_MILLIS;
                Location target = player.getLocation().getBlock().getRelative(BlockFace.DOWN).getLocation();
                boolean started = beginDungeonAt(target, player.getUniqueId(), ok -> sender.sendMessage(ok
                        ? Component.text("Арена строится по тикам, вы будете телепортированы по готовности.", NamedTextColor.GREEN)
                        : Component.text("Не удалось загрузить чанки арены.", NamedTextColor.RED)));
                if (!started) {
                    sender.sendMessage(Component.text("Поиск/загрузка уже выполняется, подождите.", NamedTextColor.RED));
                } else {
                    sender.sendMessage(Component.text("Подгружаю чанки арены асинхронно...", NamedTextColor.YELLOW));
                }
                return true;
            }
            case "stop" -> {
                if (activeDungeon == null) {
                    sender.sendMessage(Component.text("Сейчас нет активного данджа.", NamedTextColor.YELLOW));
                    return true;
                }
                cleanupDungeon(activeDungeon, true);
                activeDungeon = null;
                sender.sendMessage(Component.text("Дандж Великого Кузнеца остановлен, территория восстановлена.", NamedTextColor.GREEN));
                return true;
            }
            case "shop" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Команда только для игроков!", NamedTextColor.RED));
                    return true;
                }
                if (activeDungeon != null) {
                    if (activeDungeon.trades.isEmpty()) {
                        generateRandomBlacksmithTrades(activeDungeon);
                    }
                    openBlacksmithShop(player, activeDungeon);
                } else {
                    DungeonSession dummy = new DungeonSession(player.getWorld(), player.getLocation(),
                            BossBar.bossBar(Component.empty(), 1.0F, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS));
                    generateRandomBlacksmithTrades(dummy);
                    openBlacksmithShop(player, dummy);
                }
                return true;
            }
            case "shopcheck" -> {
                DungeonSession dummy = new DungeonSession(Bukkit.getWorlds().getFirst(), Bukkit.getWorlds().getFirst().getSpawnLocation(),
                        BossBar.bossBar(Component.empty(), 1.0F, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS));
                generateRandomBlacksmithTrades(dummy);
                int books = 0;
                for (MerchantRecipe r : dummy.trades) {
                    ItemStack res = r.getResult();
                    String line = res.getType().name() + " x" + res.getAmount() + " <- "
                            + r.getIngredients().stream().map(i -> i.getType().name() + " x" + i.getAmount()).toList()
                            + " (лимит " + r.getMaxUses() + ")";
                    if (res.getItemMeta() instanceof EnchantmentStorageMeta esm && !esm.getStoredEnchants().isEmpty()) {
                        books++;
                        line += " | овер-книга: " + esm.getStoredEnchants().entrySet().stream()
                                .map(e -> e.getKey().getKey().getKey() + " " + e.getValue()).toList();
                    }
                    sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
                }
                sender.sendMessage(Component.text("Трейдов: " + dummy.trades.size() + ", овер-книг: " + books
                        + " — все прошли проверку и сериализацию.", books > 0 ? NamedTextColor.GREEN : NamedTextColor.RED));
                return true;
            }
            case "givecore" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Команда только для игроков!", NamedTextColor.RED));
                    return true;
                }
                String id = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "cb_master";
                if (!List.of("cb_orange", "cb_purple", "cb_green", "cb_master").contains(id)) {
                    sender.sendMessage(Component.text("Ядра: cb_orange, cb_purple, cb_green, cb_master", NamedTextColor.YELLOW));
                    return true;
                }
                player.getInventory().addItem(createUpgradeCore(id));
                sender.sendMessage(Component.text("Выдано ядро " + id, NamedTextColor.GREEN));
                return true;
            }
            case "giveitem" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Команда только для игроков!", NamedTextColor.RED));
                    return true;
                }
                String id = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
                ItemStack item = switch (id) {
                    case "mace_stick" -> createMaceTexturedStick(ThreadLocalRandom.current());
                    case "spawner_boat" -> createSpawnerTexturedBoat();
                    case "slag_grapple" -> createSlagGrapple();
                    case "slag_potion" -> createMoltenSlagPotion();
                    case "anvil_totem" -> createAnvilTotem();
                    case "forge_warhammer" -> createForgeWarhammer();
                    case "vulcan_shield" -> createVulcanShield();
                    case "titan_draught" -> createTitanDraught();
                    case "slag_crossbow" -> createSlagCrossbow();
                    case "blacksmith_blade" -> createBlacksmithBlade();
                    case "blacksmith_chestplate" -> createBlacksmithChestplate();
                    case "blacksmith_helmet" -> createBlacksmithHelmet();
                    case "blacksmith_boots" -> createBlacksmithBoots();
                    default -> null;
                };
                if (item == null) {
                    sender.sendMessage(Component.text("Предметы: mace_stick, spawner_boat, slag_grapple, slag_potion, anvil_totem, forge_warhammer, vulcan_shield, titan_draught, slag_crossbow, blacksmith_blade, blacksmith_chestplate, blacksmith_helmet, blacksmith_boots", NamedTextColor.YELLOW));
                    return true;
                }
                player.getInventory().addItem(item);
                sender.sendMessage(Component.text("Выдан предмет " + id, NamedTextColor.GREEN));
                return true;
            }
            case "givebook" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Команда только для игроков!", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(Component.text("Использование: /blacksmith givebook <чары> <уровень>", NamedTextColor.YELLOW));
                    return true;
                }
                Enchantment ench = org.bukkit.Registry.ENCHANTMENT.get(NamespacedKey.minecraft(args[1].toLowerCase(Locale.ROOT)));
                int level;
                try {
                    level = Integer.parseInt(args[2]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage(Component.text("Уровень должен быть числом.", NamedTextColor.RED));
                    return true;
                }
                if (ench == null || level < 1 || level > AnvilOverlevelListener.getBookCap(ench)) {
                    int cap = ench == null ? 0 : AnvilOverlevelListener.getBookCap(ench);
                    sender.sendMessage(Component.text(ench == null
                            ? "Неизвестное зачарование."
                            : "Уровень 1.." + cap + " (Hard Cap книги).", NamedTextColor.RED));
                    return true;
                }
                player.getInventory().addItem(createOverleveledBook(ench, level,
                        "Книга Кузнеца: " + ench.getKey().getKey() + " " + level));
                sender.sendMessage(Component.text("Выдана книга.", NamedTextColor.GREEN));
                return true;
            }
            default -> {
                sender.sendMessage(Component.text("Использование: /blacksmith <spawn|here|stop|status|shop|shopcheck|givecore|giveitem|givebook>", NamedTextColor.YELLOW));
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> opts = (sender.hasPermission("greatblacksmith.admin") || sender.isOp())
                    ? List.of("spawn", "here", "stop", "status", "shop", "shopcheck", "givecore", "giveitem", "givebook")
                    : List.of("status");
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return opts.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2 && (sender.hasPermission("greatblacksmith.admin") || sender.isOp())) {
            List<String> opts = switch (args[0].toLowerCase(Locale.ROOT)) {
                case "givecore" -> List.of("cb_orange", "cb_purple", "cb_green", "cb_master");
                case "giveitem" -> List.of("mace_stick", "spawner_boat", "slag_grapple", "slag_potion", "anvil_totem", "forge_warhammer", "vulcan_shield", "titan_draught", "slag_crossbow", "blacksmith_blade", "blacksmith_chestplate", "blacksmith_helmet", "blacksmith_boots");
                case "givebook" -> List.of("sharpness", "protection", "unbreaking", "efficiency", "power", "feather_falling", "fire_aspect", "looting", "fortune", "sweeping_edge", "thorns", "knockback", "punch", "respiration", "aqua_affinity", "quick_charge", "piercing");
                default -> List.of();
            };
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return opts.stream().filter(o -> o.startsWith(prefix)).toList();
        }
        return Collections.emptyList();
    }
}
