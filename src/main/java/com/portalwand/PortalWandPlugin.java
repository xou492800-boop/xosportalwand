package com.portalwand;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Portal Wand: right click = blue portal, left click = dark red portal (crosshair based).
 * Entities and projectiles keep their momentum (speed) when travelling through.
 * Only the whitelisted UUIDs below may hold / use the item.
 */
public class PortalWandPlugin extends JavaPlugin implements Listener {

    static final Set<UUID> ALLOWED = Set.of(
            UUID.fromString("01668aa0-0c1e-44ba-b8e6-c21a8d643ffe"),  // DSBV
            UUID.fromString("c74b6b9d-8ea6-4057-99ce-257f719ec458")); // xo1u

    static final Color BLUE = Color.fromRGB(0, 120, 255);
    static final Color DARK_RED = Color.fromRGB(139, 0, 0);
    static final double MIN_EXIT_SPEED = 0.25; // blocks/tick, so walking out of a portal actually leaves it

    NamespacedKey key;
    final Map<UUID, Portal[]> portals = new HashMap<>();   // owner -> [blue, red]
    final Map<UUID, Vector> lastPos = new HashMap<>();
    final Map<UUID, Integer> lastSeen = new HashMap<>(), cooldown = new HashMap<>();
    int tick = 0;

    static class Portal {
        final World world; final Vector center, n, t, u; final double hw, hh; final Color color;
        final List<Block> anchors; final boolean vertical;
        Portal(World w, Vector c, Vector n, double hw, double hh, Color col, List<Block> anchors) {
            this.world = w; this.center = c; this.n = n; this.hw = hw; this.hh = hh; this.color = col; this.anchors = anchors;
            this.vertical = Math.abs(n.getY()) < 0.5;
            this.u = vertical ? new Vector(0, 1, 0) : new Vector(0, 0, 1);
            this.t = u.clone().crossProduct(n).normalize();      // (t, u, n) is right-handed
        }
        boolean valid() { for (Block b : anchors) if (!b.getType().isSolid()) return false; return true; }
        Location loc() { return new Location(world, center.getX(), center.getY(), center.getZ()); }
    }

    @Override public void onEnable() {
        key = new NamespacedKey(this, "portal_wand");
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::tickEntities, 1, 1);
        getServer().getScheduler().runTaskTimer(this, this::drawPortals, 2, 2);
        getServer().getScheduler().runTaskTimer(this, this::enforceWhitelist, 20, 10);
    }

    // ---------- item + whitelist ----------
    ItemStack createWand() {
        ItemStack it = new ItemStack(Material.STICK);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Portal Wand", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("Right click: blue portal", NamedTextColor.BLUE).decoration(TextDecoration.ITALIC, false),
                Component.text("Left click: dark red portal", NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, false)));
        m.setEnchantmentGlintOverride(true);
        m.setMaxStackSize(1);
        m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }
    boolean isWand(ItemStack it) {
        return it != null && it.getType() == Material.STICK && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }
    boolean allowed(Player p) { return ALLOWED.contains(p.getUniqueId()); }
    void strip(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) if (isWand(inv.getItem(i))) inv.setItem(i, null);
        if (isWand(p.getItemOnCursor())) p.setItemOnCursor(null);
    }
    void enforceWhitelist() { for (Player p : Bukkit.getOnlinePlayers()) if (!allowed(p)) strip(p); }

    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
        if (!allowed(p)) { p.sendMessage(Component.text("You are not allowed to use this item.", NamedTextColor.RED)); return true; }
        p.getInventory().addItem(createWand());
        p.sendMessage(Component.text("Portal Wand given.", NamedTextColor.AQUA));
        return true;
    }

    @EventHandler public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && !allowed(p) && isWand(e.getItem().getItemStack())) e.setCancelled(true);
    }
    @EventHandler public void onJoin(PlayerJoinEvent e) { if (!allowed(e.getPlayer())) strip(e.getPlayer()); }
    @EventHandler public void onHeld(PlayerItemHeldEvent e) {
        if (!allowed(e.getPlayer())) strip(e.getPlayer());
    }
    @EventHandler public void onMelee(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && isWand(p.getInventory().getItemInMainHand())) e.setCancelled(true);
    }

    // ---------- firing ----------
    @EventHandler public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !isWand(e.getItem())) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!allowed(p)) { strip(p); return; }
        Action ac = e.getAction();
        int slot = (ac == Action.LEFT_CLICK_AIR || ac == Action.LEFT_CLICK_BLOCK) ? 1
                 : (ac == Action.RIGHT_CLICK_AIR || ac == Action.RIGHT_CLICK_BLOCK) ? 0 : -1;
        if (slot >= 0) fire(p, slot);
    }

    void fire(Player p, int slot) {
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTraceBlocks(eye, eye.getDirection(), 128, FluidCollisionMode.NEVER, true);
        Portal np = (r == null || r.getHitBlock() == null || r.getHitBlockFace() == null) ? null
                : buildPortal(r.getHitBlock(), r.getHitBlockFace(), r.getHitPosition(), slot == 0 ? BLUE : DARK_RED);
        if (np == null) { p.playSound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f); return; }
        portals.computeIfAbsent(p.getUniqueId(), k -> new Portal[2])[slot] = np;
        np.world.playSound(np.loc(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, slot == 0 ? 1.4f : 0.7f);
    }

    boolean ok(Block b, BlockFace f) { return b.getType().isSolid() && !b.getRelative(f).getType().isSolid(); }

    Portal buildPortal(Block hb, BlockFace f, Vector hit, Color col) {
        Vector n = f.getDirection();
        World w = hb.getWorld();
        if (f == BlockFace.UP || f == BlockFace.DOWN) {
            if (!ok(hb, f)) return null;
            Vector c = new Vector(hb.getX() + 0.5, f == BlockFace.UP ? hb.getY() + 1.02 : hb.getY() - 0.02, hb.getZ() + 0.5);
            return new Portal(w, c, n, 0.5, 0.5, col, List.of(hb));
        }
        int[] opts = (hit.getY() - hb.getY()) >= 0.5 ? new int[]{0, -1} : new int[]{-1, 0};
        for (int off : opts) {
            Block bot = hb.getRelative(0, off, 0), top = bot.getRelative(0, 1, 0);
            if (ok(bot, f) && ok(top, f)) {
                Vector c = new Vector(hb.getX() + 0.5 + n.getX() * 0.52, bot.getY() + 1.0, hb.getZ() + 0.5 + n.getZ() * 0.52);
                return new Portal(w, c, n, 0.5, 1.0, col, List.of(bot, top));
            }
        }
        return null;
    }

    // ---------- visuals ----------
    void drawPortals() {
        Random rnd = new Random();
        for (Portal[] pair : portals.values()) for (int i = 0; i < 2; i++) {
            Portal a = pair[i];
            if (a == null) continue;
            if (!a.valid()) { pair[i] = null; continue; }
            Particle.DustOptions d = new Particle.DustOptions(a.color, 1.3f);
            for (int k = 0; k < 32; k++) {
                double ang = k * Math.PI * 2 / 32;
                Vector p = a.center.clone().add(a.t.clone().multiply(Math.cos(ang) * a.hw)).add(a.u.clone().multiply(Math.sin(ang) * a.hh));
                a.world.spawnParticle(Particle.DUST, p.getX(), p.getY(), p.getZ(), 1, 0, 0, 0, 0, d);
            }
            Particle.DustOptions inner = new Particle.DustOptions(a.color, 0.7f);
            for (int k = 0; k < 6; k++) {
                double rr = Math.sqrt(rnd.nextDouble()), ang = rnd.nextDouble() * Math.PI * 2;
                Vector p = a.center.clone().add(a.t.clone().multiply(Math.cos(ang) * a.hw * rr)).add(a.u.clone().multiply(Math.sin(ang) * a.hh * rr));
                a.world.spawnParticle(Particle.DUST, p.getX(), p.getY(), p.getZ(), 1, 0, 0, 0, 0, inner);
            }
        }
    }

    // ---------- teleport maths ----------
    /** Rotates a direction/velocity from A's frame to B's frame: into A becomes out of B, momentum magnitude unchanged. */
    Vector mapDir(Vector v, Portal a, Portal b) {
        double vt = v.dot(a.t), vu = v.dot(a.u), vn = v.dot(a.n);
        return b.t.clone().multiply(-vt).add(b.u.clone().multiply(vu)).add(b.n.clone().multiply(-vn));
    }

    void tickEntities() {
        tick++;
        for (Portal[] pair : portals.values()) for (int i = 0; i < 2; i++) {
            Portal a = pair[i], b = pair[1 - i];
            if (a == null || b == null) continue;
            for (Entity e : a.world.getNearbyEntities(a.loc(), 2, 2, 2)) {
                if (e instanceof Projectile || e.isInsideVehicle()) continue;
                if (e instanceof Player pl && pl.getGameMode() == GameMode.SPECTATOR) continue;
                handle(e, a, b);
            }
        }
        if (tick % 200 == 0) { lastSeen.values().removeIf(s -> s < tick - 40); lastPos.keySet().retainAll(lastSeen.keySet()); cooldown.values().removeIf(c -> c < tick); }
    }

    void handle(Entity e, Portal a, Portal b) {
        UUID id = e.getUniqueId();
        BoundingBox bb = e.getBoundingBox();
        Vector ctr = bb.getCenter();
        Vector prev = lastPos.get(id); Integer seen = lastSeen.get(id);
        Vector vel = (prev != null && seen != null && tick - seen <= 2) ? ctr.clone().subtract(prev) : new Vector();
        lastPos.put(id, ctr); lastSeen.put(id, tick);
        if (cooldown.getOrDefault(id, 0) > tick) return;

        double hx = bb.getWidthX() / 2, hy = bb.getHeight() / 2, hz = bb.getWidthZ() / 2;
        Vector rel = ctr.clone().subtract(a.center);
        double pt = rel.dot(a.t), pu = rel.dot(a.u), pn = rel.dot(a.n);
        double sdist = pn - (hx * Math.abs(a.n.getX()) + hy * Math.abs(a.n.getY()) + hz * Math.abs(a.n.getZ()));
        if (sdist > 0.12 || pn < -0.2) return;
        if (Math.abs(pt) > a.hw + 0.1 || Math.abs(pu) > a.hh + (a.vertical ? 0 : 0.1)) return;

        boolean going = vel.dot(a.n) < -0.01;
        if (!going && e instanceof Player p) {   // pressing into a wall portal: use look direction as intent
            Vector look = p.getLocation().getDirection();
            if (p.getCurrentInput().isForward() && look.dot(a.n) < -0.3) {
                vel = look.clone().setY(0).normalize().multiply(p.isSprinting() ? 0.28 : 0.22);
                going = true;
            }
        }
        if (!going && !a.vertical && a.n.getY() > 0) going = true;   // standing on / stepping onto a floor portal
        if (!going) return;

        double lo = -b.hh + hy, hi = Math.max(lo, b.hh - hy);
        double ou = b.vertical ? Math.max(lo, Math.min(hi, a.vertical ? pu : lo)) : Math.max(-b.hh, Math.min(b.hh, pu));
        double supB = hx * Math.abs(b.n.getX()) + hy * Math.abs(b.n.getY()) + hz * Math.abs(b.n.getZ());
        Vector nc = b.center.clone().add(b.t.clone().multiply(-pt)).add(b.u.clone().multiply(ou)).add(b.n.clone().multiply(supB + 0.1));

        Vector nv = mapDir(vel, a, b);
        double vn = nv.dot(b.n);
        if (vn < MIN_EXIT_SPEED) nv.add(b.n.clone().multiply(MIN_EXIT_SPEED - vn));

        Location to = new Location(b.world, nc.getX(), nc.getY() - hy, nc.getZ());
        if (e instanceof LivingEntity) to.setDirection(mapDir(e.getLocation().getDirection(), a, b));
        else { to.setYaw(e.getLocation().getYaw()); to.setPitch(e.getLocation().getPitch()); }

        e.teleport(to);
        e.setVelocity(nv);
        e.setFallDistance(0);
        cooldown.put(id, tick + 6);
        lastPos.put(id, nc); lastSeen.put(id, tick);
        a.world.playSound(a.loc(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.2f);
        b.world.playSound(b.loc(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.2f);
    }

    // ---------- projectiles ----------
    @EventHandler public void onProjectileHit(ProjectileHitEvent ev) {
        Block hb = ev.getHitBlock(); BlockFace face = ev.getHitBlockFace();
        if (hb == null || face == null) return;
        Projectile pr = ev.getEntity();
        Vector n = face.getDirection(), pos = pr.getLocation().toVector();
        for (Portal[] pair : portals.values()) for (int i = 0; i < 2; i++) {
            Portal a = pair[i], b = pair[1 - i];
            if (a == null || b == null || a.world != pr.getWorld() || a.n.distanceSquared(n) > 0.01) continue;
            Vector rel = pos.clone().subtract(a.center);
            double pt = rel.dot(a.t), pu = rel.dot(a.u);
            if (Math.abs(pt) > a.hw + 0.1 || Math.abs(pu) > a.hh + 0.1 || Math.abs(rel.dot(a.n)) > 0.8) continue;
            ev.setCancelled(true);
            Vector nv = mapDir(pr.getVelocity(), a, b);                // same speed, rotated to leave B
            double ouLim = Math.max(0, b.hh - 0.1);
            Vector np = b.center.clone().add(b.t.clone().multiply(-pt))
                    .add(b.u.clone().multiply(Math.max(-ouLim, Math.min(ouLim, pu)))).add(b.n.clone().multiply(0.4));
            pr.teleport(new Location(b.world, np.getX(), np.getY(), np.getZ()));
            pr.setVelocity(nv);
            b.world.playSound(b.loc(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.5f);
            return;
        }
    }
}
