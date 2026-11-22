package dev.flashclips;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out (client side) when you killed another player.
 * Two sources, deduplicated per victim:
 *  - a vanilla death message ("death.*" translation) or a custom regex naming you as the killer
 *  - a player you hit (or who stood next to a crystal/anchor you popped) dying shortly after
 */
public final class KillTracker {
    private static final Map<UUID, Long> lastHitByMe = new HashMap<>();
    private static final Map<UUID, Long> lastKillTick = new HashMap<>();
    private static final Set<UUID> deadPlayers = new HashSet<>();
    private static List<Pattern> customPatterns;

    private KillTracker() {}

    public static void reset() {
        lastHitByMe.clear();
        lastKillTick.clear();
        deadPlayers.clear();
    }

    // ---- "I hurt them" tracking ----

    public static void onAttack(Player attacker, Entity target) {
        Minecraft mc = Minecraft.getInstance();
        if (attacker != mc.player) return;
        if (target instanceof Player victim && victim != attacker) {
            lastHitByMe.put(victim.getUUID(), FlashClips.ticks());
        } else if (target instanceof EndCrystal) {
            creditNearby(target.position());
        }
    }

    public static void onUseBlock(Player player, Level level, BlockHitResult hit) {
        Minecraft mc = Minecraft.getInstance();
        if (player != mc.player) return;
        BlockState state = level.getBlockState(hit.getBlockPos());
        if (state.getBlock() instanceof RespawnAnchorBlock && state.getValue(RespawnAnchorBlock.CHARGE) > 0) {
            creditNearby(Vec3.atCenterOf(hit.getBlockPos()));
        }
    }

    private static void creditNearby(Vec3 pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        double r2 = FlashClips.CONFIG.explosionCreditRadius * FlashClips.CONFIG.explosionCreditRadius;
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p != mc.player && p.distanceToSqr(pos) <= r2) {
                lastHitByMe.put(p.getUUID(), FlashClips.ticks());
            }
        }
    }

    // ---- death detection ----

    public static void tick(Minecraft mc) {
        if (mc.level == null) return;
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p == mc.player) continue;
            if (p.isDeadOrDying()) {
                if (deadPlayers.add(p.getUUID())) onPlayerDied(p);
            } else {
                deadPlayers.remove(p.getUUID());
            }
        }
        long now = FlashClips.ticks();
        lastHitByMe.values().removeIf(t -> now - t > 20L * 60);
        lastKillTick.values().removeIf(t -> now - t > 20L * 60);
    }

    /** Entity event 3 = death animation; called from LivingEntityMixin. */
    public static void onEntityEvent(LivingEntity entity, byte id) {
        if (id != 3) return;
        Minecraft mc = Minecraft.getInstance();
        if (entity instanceof Player p && p != mc.player && deadPlayers.add(p.getUUID())) {
            onPlayerDied(p);
        }
    }

    private static void onPlayerDied(Player p) {
        if (!FlashClips.CONFIG.detectKillsFromHits) return;
        Long hit = lastHitByMe.get(p.getUUID());
        if (hit != null && FlashClips.ticks() - hit <= FlashClips.CONFIG.killCreditSeconds * 20L) {
            registerKill(p.getUUID(), p.getGameProfile().name());
        }
    }

    // ---- chat death messages ----

    public static void onGameMessage(Component message) {
        if (!FlashClips.CONFIG.detectKillsFromChat) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String me = mc.player.getGameProfile().name();

        if (message.getContents() instanceof TranslatableContents tc && tc.getKey().startsWith("death.")) {
            Object[] args = tc.getArgs();
            if (args.length >= 2) {
                String victim = argToString(args[0]);
                String killer = argToString(args[1]);
                if (containsName(killer, me) && !containsName(victim, me)) {
                    killFromName(victim);
                }
            }
            return;
        }

        String text = message.getString();
        if (!text.contains(me)) return;
        for (Pattern pattern : patterns()) {
            Matcher m = pattern.matcher(text);
            while (m.find()) {
                try {
                    String killer = m.group("killer");
                    String victim = m.group("victim");
                    if (killer.equalsIgnoreCase(me) && !victim.equalsIgnoreCase(me)) {
                        killFromName(victim);
                        return;
                    }
                } catch (IllegalArgumentException ignored) {
                    // pattern without the named groups
                }
            }
        }
    }

    private static List<Pattern> patterns() {
        if (customPatterns == null) {
            List<Pattern> list = new ArrayList<>();
            for (String s : FlashClips.CONFIG.customKillMessagePatterns) {
                try {
                    list.add(Pattern.compile(s, Pattern.CASE_INSENSITIVE));
                } catch (Exception e) {
                    FlashClips.LOGGER.warn("Invalid kill message pattern {}: {}", s, e.getMessage());
                }
            }
            customPatterns = list;
        }
        return customPatterns;
    }

    private static String argToString(Object arg) {
        if (arg instanceof Component c) return c.getString();
        return String.valueOf(arg);
    }

    private static boolean containsName(String text, String name) {
        return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE)
                .matcher(text).find();
    }

    /** Victim text may include rank prefixes etc.; match it against the tab list. */
    private static void killFromName(String victimText) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener conn = mc.getConnection();
        if (conn == null) return;
        PlayerInfo best = conn.getPlayerInfoIgnoreCase(victimText.trim());
        if (best == null) {
            for (PlayerInfo info : conn.getOnlinePlayers()) {
                String name = info.getProfile().name();
                if (containsName(victimText, name) && (best == null || name.length() > best.getProfile().name().length())) {
                    best = info;
                }
            }
        }
        if (best != null) {
            registerKill(best.getProfile().id(), best.getProfile().name());
        } else {
            FlashClips.LOGGER.info("Kill message for '{}' but that player isn't in the tab list", victimText);
            registerKill(null, victimText.replaceAll("[^A-Za-z0-9_]", ""));
        }
    }

    private static void registerKill(UUID uuid, String name) {
        long now = FlashClips.ticks();
        UUID key = uuid != null ? uuid : UUID.nameUUIDFromBytes(("unknown:" + name).getBytes());
        Long last = lastKillTick.get(key);
        if (last != null && now - last < 100) return; // same kill seen twice (chat + death)
        lastKillTick.put(key, now);
        lastHitByMe.remove(key);
        FlashClips.LOGGER.info("Detected kill: {} ({})", name, uuid);
        ScreenClips.onEvent("kill");
    }

    /** Totem pop (entity event 35); called from ClientPacketListenerMixin on the main thread. */
    public static void onTotemPop(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (!(entity instanceof Player p) || p == mc.player) return;
        Long hit = lastHitByMe.get(p.getUUID());
        if (hit != null && FlashClips.ticks() - hit <= FlashClips.CONFIG.killCreditSeconds * 20L) {
            ScreenClips.onEvent("pop");
        }
    }
}
