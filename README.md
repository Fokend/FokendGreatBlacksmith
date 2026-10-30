Вот переработанная и технически дополненная версия README. Из текста убран типичный «маркетинговый шум» нейросетей (вроде *«погрузитесь в захватывающий мир ковки»* и лишних эмодзи в каждом заголовке), добавлены реальные механики (NBT/PDC теги, стейт-машина, структура `config.yml`, аргументы команд) и оформление под реальный Open Source / SpigotMC проект.

---

# FokendGreatBlacksmith

A lightweight, event-driven PaperMC dungeon and progression plugin for **Paper 1.21.11** (Java 21). Combines an automated arena event cycle, persistent terrain rollback, wave difficulty scaling, PDC-backed custom weaponry, and an uncapped anvil upgrade pipeline.

---

## Technical Overview

* **Engine:** PaperMC API (`1.21.11-R0.1-SNAPSHOT`)
* **Java Version:** OpenJDK 21
* **Data Storage:** PersistentDataContainer (PDC) for custom item identification and core tags
* **Rollback Engine:** In-memory block state cache (`BlockStateSnapshot`) with async restore on cycle termination
* **Event Loop:** BukkitScheduler-driven state machine

---

## State Machine & Event Lifecycle

The dungeon operates autonomously on a configurable hourly scheduler (`dungeon.interval-ticks`).

```
[IDLE / WAITING] 
       │ (Player enters trigger radius / admin force)
       ▼
  [COUNTDOWN]  ─── 5s title countdown + sound cues
       │
       ▼
    [WAVES]    ─── Mob spawning + dynamic HP/damage scaling
       │
   ┌───┴────────────────────────┐
   ▼                            ▼
[REWARD]                     [FAILED]
3-minute extraction window   1-minute cleanup timer
Zhitak merchant active       Zero reward drops
   │                            │
   └───────────┬────────────────┘
               ▼
           [ROLLBACK]
Restores blocks, tile entities, clears stray mobs

```

### Region Protection & Rollback

While in `COUNTDOWN`, `WAVES`, or `REWARD` states:

* Block placement/breaking by non-admin players is cancelled.
* Explosion damage (`BlockExplodeEvent`, `EntityExplodeEvent`) is intercepted; blocks are tracked rather than destroyed.
* Fire spread and liquid flow outside the designated bounding box are suppressed.
* Upon transition to `ROLLBACK`, cached original block states and tile data are restored sequentially to minimize single-tick server lag.

---

## Custom Items & Combat System

Items are tagged via NamespacedKeys to prevent conflicts with vanilla survival mechanics.

| Item ID | Base Material | Mechanics / Special Attributes |
| --- | --- | --- |
| `FORGE_WARHAMMER` | `NETHERITE_AXE` | -15% Attack Speed, +4 Attack Damage. Critical strikes trigger a 3-block seismic shockwave (AOE stun + knockup). |
| `VULCAN_SHIELD` | `SHIELD` | Blocking melee attacks builds *Heat Stacks* (max 5). Releasing block at max stacks triggers an explosion that ignites attackers. |
| `SLAG_CROSSBOW` | `CROSSBOW` | Converts standard arrows into explosive Slag Bolts. Deals 120% kinetic damage and leaves a 4-second molten puddle. |
| `BLACKSMITH_BLADE` | `NETHERITE_SWORD` | Deals +25% bonus damage against armored enemies. Grants permanent fire immunity while held. |
| `BLACKSMITH_ARMOR` | `NETHERITE_SET` | **Full Set Bonus:** Complete immunity to magma/lava damage, 25% anvil repair cost reduction, and passive Resistance I inside active dungeons. |
| `SLAG_GRAPPLE` | `FISHING_ROD` | Hooks terrain to pull the user forward (10s cooldown). Pulls mobs toward the player if hooked directly. |
| `ANVIL_TOTEM` | `TOTEM_OF_UNDYING` | On lethal hit: grants Absorption IV, Regeneration II, and drops an anvil projectile directly onto the killer. |
| `MACE_STICK` | `STICK` | Lightweight blunt weapon. High knockback factor with 15% armor penetration chance. |
| `SPAWNER_BOAT` | `OAK_BOAT` | Utility vessel capable of holding and relocating vanilla spawners when used with proper permissions. |
| `TITAN_DRAUGHT` | `POTION` | Grants Resistance II, Strength II, and Slowness I for 45 seconds. |
| `SLAG_POTION` | `SPLASH_POTION` | Spawns a lingering magma pool (`AREA_EFFECT_CLOUD`) dealing continuous fire damage over 6 seconds. |

---

## Anvil Enchantment Uncapper & Cores

The plugin overrides `PrepareAnvilEvent` and `AnvilRepairEvent` to bypass the vanilla enchantment level cap (e.g., Sharpness VI–X, Protection V–VIII) using custom catalysts called **Cores**.

### Core Types

* **Orange Core (`ORANGE_CORE`):** Boosts Nether/Fire enchantments beyond limits (*Fire Aspect, Flame, Blast Protection*).
* **Purple Core (`PURPLE_CORE`):** Uncaps Arcane and Utility enchantments (*Fortune, Looting, Unbreaking, Efficiency*).
* **Green Core (`GREEN_CORE`):** Expands Defensive and Environmental stats (*Protection, Projectile Protection, Feather Falling*).
* **Master Core (`MASTER_CORE`):** Universal catalyst. Guarantees 100% anvil combination success and bypasses the *"Too Expensive!"* repair limit completely.

---

## Merchant NPC: Zhitak

Once a dungeon successfully reaches the `REWARD` state, the merchant **Zhitak** spawns at the arena center.

* Trades are randomized per cycle using a weighted loot table (`trades.yml`).
* Accepts raw dungeon drops (Slag, Broken Core Shards, Refined Scrap) in exchange for custom books, cores, and gear.
* Unused merchant instances despawn strictly when the 3-minute reward timer expires.

---

## Configuration Preview

```yaml
dungeon:
  auto-spawn: true
  interval-minutes: 60
  countdown-seconds: 5
  reward-duration-seconds: 180
  failed-cleanup-seconds: 60
  
scaling:
  base-wave-count: 3
  extra-waves-per-player: 1
  health-multiplier-per-player: 0.15 # +15% mob HP per additional player

anvil:
  bypass-too-expensive: true
  max-enchant-level: 10
  break-chance-on-fail: 0.10

protection:
  prevent-block-break: true
  prevent-block-place: true
  prevent-explosions: true

```

---

## Commands & Permissions

### Commands

| Command | Arguments | Permission | Description |
| --- | --- | --- | --- |
| `/blacksmith` | `status` | `greatblacksmith.use` | Displays current dungeon state and time to next event |
| `/blacksmith` | `spawn [x y z]` | `greatblacksmith.admin` | Forces a dungeon spawn at target/current location |
| `/blacksmith` | `stop [force]` | `greatblacksmith.admin` | Stops the active event and triggers instant rollback |
| `/blacksmith` | `shop` | `greatblacksmith.admin` | Opens Zhitak's current trade inventory manually |
| `/blacksmith` | `givecore <player> <type> [amount]` | `greatblacksmith.admin` | Grants upgrade cores (`orange`, `purple`, `green`, `master`) |
| `/blacksmith` | `giveitem <player> <item_id> [amount]` | `greatblacksmith.admin` | Gives specified custom weapon, armor, or tool |
| `/blacksmith` | `givebook <player> <enchant> <level>` | `greatblacksmith.admin` | Generates a book with uncapped enchantment levels |

### Permissions

* `greatblacksmith.use` — Basic access to `/blacksmith status` (Default: `true`).
* `greatblacksmith.admin` — Full administrative control, spawning, and item issuance (Default: `op`).

---

## Compilation

Build the `.jar` using Gradle and JDK 21:

```bash
# Clone the repository
git clone https://github.com/fokend/FokendGreatBlacksmith.git
cd FokendGreatBlacksmith

# Run build task
./gradlew clean build

```

The compiled output will be located in:

```
build/libs/FokendGreatBlacksmith-1.0.0.jar

```

---

## Developer / Author

* **Author:** Fokend       Idea:SpIIdraNer 
* **Target Environment:** PaperMC 1.21.11 / Purpur / Folia (single-threaded scheduler mode)
