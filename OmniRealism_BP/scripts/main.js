import { world, system, BlockPermutation, ItemStack } from "@minecraft/server";
import { ActionFormData } from "@minecraft/server-ui";

const TICK_RATE = 20;
const GRID_PERIOD = 20;
const THERMAL_PERIOD = 10;
const STRUCTURE_PERIOD = 5;
const SCAN_BUDGET = 18;
const STATE_KEY = "omni:state:v1";
const DIRECTIONS = [[1,0,0],[-1,0,0],[0,1,0],[0,-1,0],[0,0,1],[0,0,-1]];
const CONDUCTORS = new Map([["omni:lv_cable", { tier: "LV", voltage: 240, ampacity: 180, resistance: 0.018 }], ["omni:hv_cable", { tier: "HV", voltage: 33000, ampacity: 90, resistance: 0.004 }]]);
const MACHINES = new Set(["omni:coal_turbine", "omni:transformer_substation"]);
const SOLID_SUPPORT = new Set(["minecraft:stone", "minecraft:deepslate", "minecraft:bricks", "minecraft:stone_bricks", "minecraft:polished_deepslate", "minecraft:iron_block", "minecraft:oak_log", "omni:support_beam"]);
const STRUCTURAL = new Set(["minecraft:stone", "minecraft:deepslate", "minecraft:bricks", "minecraft:stone_bricks", "minecraft:polished_deepslate", "minecraft:concrete", "minecraft:white_concrete", "minecraft:gray_concrete"]);
const state = { machines: {}, stress: {}, wind: {}, dirty: true, scanCursor: 0 };

function key(d, p) { return `${d.id}:${Math.floor(p.x)},${Math.floor(p.y)},${Math.floor(p.z)}`; }
function posFromKey(k) { const a = k.split(":"); return { x: Number(a[a.length - 3]), y: Number(a[a.length - 2]), z: Number(a[a.length - 1]) }; }
function chunkKey(d, p) { return `${d.id}:${Math.floor(p.x / 16)},${Math.floor(p.z / 16)}`; }
function adjacent(p) { return DIRECTIONS.map(([x,y,z]) => ({ x:p.x+x,y:p.y+y,z:p.z+z })); }
function blockAt(d, p) { try { return d.getBlock(p); } catch { return undefined; } }
function typeAt(d, p) { return blockAt(d, p)?.typeId ?? "minecraft:air"; }
function isAir(id) { return id === "minecraft:air" || id === "minecraft:cave_air"; }
function save() { world.setDynamicProperty(STATE_KEY, JSON.stringify(state)); }
function load() { const raw = world.getDynamicProperty(STATE_KEY); if (typeof raw !== "string") return; try { const restored = JSON.parse(raw); Object.assign(state, restored); } catch { world.sendMessage("§c[OmniRealism] Persistent state was corrupt; a fresh state was initialized."); } }
function machineDefaults(type) { return type === "omni:coal_turbine" ? { type, fuel: 0, temperature: 293, health: 100, outputKW: 0, demandKW: 0, deliveredKW: 0, tier: "MV", online: true } : { type, temperature: 293, health: 100, outputKW: 0, demandKW: 0, deliveredKW: 0, inputTier: "HV", outputTier: "LV", online: true }; }
function persistMachineBlock(block, machine) { try { block.setDynamicProperty("omni:machine", JSON.stringify(machine)); } catch { /* World state remains the authoritative fallback for blocks without dynamic properties. */ } }
function ensureMachine(block) { const id = key(block.dimension, block.location); if (!state.machines[id]) { let saved; try { saved = block.getDynamicProperty("omni:machine"); } catch { saved = undefined; } state.machines[id] = typeof saved === "string" ? JSON.parse(saved) : machineDefaults(block.typeId); } return state.machines[id]; }
function removeAt(d, p) { delete state.machines[key(d,p)]; delete state.stress[key(d,p)]; state.dirty = true; }
function nearbyMachine(d, p) { for (const q of adjacent(p)) { const b = blockAt(d,q); if (b && MACHINES.has(b.typeId)) return b; } return undefined; }

function registerComponents(registry) {
  registry.registerCustomComponent("omni:machine", {
    onPlace(e) { ensureMachine(e.block); state.dirty = true; save(); },
    onPlayerDestroy(e) { removeAt(e.dimension, e.block.location); save(); }
  });
  registry.registerCustomComponent("omni:conductor", {
    onPlace() { state.dirty = true; },
    onPlayerDestroy(e) { state.dirty = true; removeAt(e.dimension, e.block.location); }
  });
  registry.registerCustomComponent("omni:support", {
    onPlace() { state.dirty = true; },
    onPlayerDestroy(e) { queueIntegrity(e.dimension, e.block.location); }
  });
}
world.beforeEvents.worldInitialize.subscribe(e => registerComponents(e.blockComponentRegistry));

function buildGraph(dimension) {
  const graph = new Map();
  const nodes = Object.keys(state.machines).filter(k => k.startsWith(`${dimension.id}:`));
  for (const id of nodes) graph.set(id, { edges: [], machine: state.machines[id] });
  for (const id of nodes) {
    const start = posFromKey(id); const queue = [{ p:start, distance:0 }]; const seen = new Set([key(dimension,start)]);
    while (queue.length) {
      const current = queue.shift();
      if (current.distance >= 64) continue;
      for (const p of adjacent(current.p)) {
        const b = blockAt(dimension,p); if (!b) continue;
        const pk = key(dimension,p); if (seen.has(pk)) continue; seen.add(pk);
        if (MACHINES.has(b.typeId) && pk !== id && graph.has(pk)) { graph.get(id).edges.push({ to:pk, length:current.distance + 1, conductor: current.conductor }); continue; }
        const c = CONDUCTORS.get(b.typeId); if (c) queue.push({ p, distance:current.distance + 1, conductor:c });
      }
    }
  }
  return graph;
}
function solveGrid(dimension) {
  const graph = buildGraph(dimension); const visited = new Set();
  for (const start of graph.keys()) {
    if (visited.has(start)) continue;
    const component = []; const queue = [start]; visited.add(start);
    while (queue.length) { const id = queue.shift(); component.push(id); for (const edge of graph.get(id).edges) if (!visited.has(edge.to)) { visited.add(edge.to); queue.push(edge.to); } }
    let supply = 0, demand = 0;
    for (const id of component) { const m = graph.get(id).machine; if (!m.online) continue; if (m.type === "omni:coal_turbine") supply += m.outputKW; else demand += m.demandKW; }
    const ratio = demand === 0 ? 1 : Math.min(1, supply / demand);
    for (const id of component) {
      const m = graph.get(id).machine; m.lineLossKW = 0; m.deliveredKW = m.type === "omni:transformer_substation" ? m.demandKW * ratio : 0;
      for (const edge of graph.get(id).edges) {
        if (!edge.conductor) continue; const current = (m.deliveredKW * 1000) / Math.max(edge.conductor.voltage, 1); const loss = current * current * edge.conductor.resistance * edge.length / 1000;
        if (current > edge.conductor.ampacity) overloadCable(dimension, posFromKey(id), edge, current); m.lineLossKW = (m.lineLossKW ?? 0) + loss;
      }
      m.brownout = ratio < 0.999;
    }
  }
}
function overloadCable(d, source, edge, current) { const direction = posFromKey(edge.to); const p = { x: Math.round((source.x + direction.x) / 2), y: Math.round((source.y + direction.y) / 2), z: Math.round((source.z + direction.z) / 2) }; d.spawnParticle("minecraft:basic_flame_particle", { x:p.x+.5,y:p.y+.5,z:p.z+.5 }); d.createExplosion({x:p.x+.5,y:p.y+.5,z:p.z+.5}, Math.min(3, 1 + current / edge.conductor.ampacity), { breaksBlocks: true, causesFire: true }); }

function ambient(d,p) { const here = typeAt(d,p); const above = typeAt(d,{x:p.x,y:p.y+1,z:p.z}); if (here.includes("water") || above.includes("water")) return 285; if (here.includes("ice") || above.includes("ice")) return 270; return 293; }
function thermalTick(dimension) {
  for (const [id,m] of Object.entries(state.machines)) {
    if (!id.startsWith(`${dimension.id}:`)) continue; const p = posFromKey(id); const block = blockAt(dimension,p); if (!block || block.typeId !== m.type) { delete state.machines[id]; continue; }
    if (m.type === "omni:coal_turbine" && m.online && m.fuel > 0) { m.fuel--; m.outputKW = 180 + Math.min(120, m.fuel / 10); m.temperature += 4.6; } else if (m.type === "omni:coal_turbine") m.outputKW = 0;
    if (m.type === "omni:transformer_substation") m.temperature += m.deliveredKW * 0.015;
    m.temperature += (ambient(dimension,p) - m.temperature) * 0.065;
    if (m.temperature > 700) { m.health -= 0.35; dimension.spawnParticle("minecraft:basic_smoke_particle", {x:p.x+.5,y:p.y+1,z:p.z+.5}); }
    if (m.temperature > 1100 || m.health <= 0) { dimension.createExplosion({x:p.x+.5,y:p.y+.5,z:p.z+.5}, 4, {breaksBlocks:true,causesFire:true}); delete state.machines[id]; }
  }
}

const integrityQueue = [];
function queueIntegrity(d,p) { integrityQueue.push({ d, p: {x:Math.floor(p.x),y:Math.floor(p.y),z:Math.floor(p.z)} }); }
function supported(d,p) { for (let radius=1; radius<=4; radius++) for (const dx of [-radius,0,radius]) for (const dz of [-radius,0,radius]) { const b = typeAt(d,{x:p.x+dx,y:p.y-1,z:p.z+dz}); if (SOLID_SUPPORT.has(b)) return true; } return false; }
function collapseBlock(d, p, typeId) { d.setBlockType(p, "minecraft:air"); try { d.spawnEntity("minecraft:falling_block", { x:p.x+.5, y:p.y+.5, z:p.z+.5 }); } catch { d.spawnItem(new ItemStack(typeId), { x:p.x+.5, y:p.y+.5, z:p.z+.5 }); } d.spawnParticle("minecraft:basic_smoke_particle", {x:p.x+.5,y:p.y+.5,z:p.z+.5}); }
function structureTick() { for (let i=0;i<SCAN_BUDGET && integrityQueue.length;i++) { const task = integrityQueue.shift(); const d=task.d; const root=task.p; const q=[root]; const seen=new Set(); while(q.length && seen.size<96) { const p=q.shift(); const k=key(d,p); if(seen.has(k)) continue; seen.add(k); const id=typeAt(d,p); if(!STRUCTURAL.has(id)) continue; const isSupported=supported(d,p); state.stress[k]={support:isSupported, checked:system.currentTick}; if(!isSupported) { collapseBlock(d, p, id); for(const n of adjacent(p)) if(n.y>=p.y) q.push(n); } } } }

function windFor(d,p) { const ck=chunkKey(d,p); const phase=Math.floor(system.currentTick/600); const seed=(Math.abs((p.x*73856093)^(p.z*19349663)^phase)%628)/100; const value={x:Math.cos(seed)*0.65,z:Math.sin(seed)*0.65,speed:0.35+(Math.abs(Math.sin(seed*1.7))*0.65)}; state.wind[ck]=value; return value; }
function fellTree(d, root, rootType) { if (!rootType.endsWith("_log")) return; const q=[root], seen=new Set(), logs=[]; while(q.length && logs.length<192) { const p=q.shift(), k=key(d,p); if(seen.has(k)) continue; seen.add(k); const id=typeAt(d,p); if(id.endsWith("_log")){logs.push({p,id}); for(const n of adjacent(p)) q.push(n);} } if(logs.length<2) return; system.run(()=>{ for(const node of logs){ if(typeAt(d,node.p)===node.id){ d.setBlockType(node.p,"minecraft:air"); d.spawnItem(new ItemStack(node.id),{x:node.p.x+.5,y:node.p.y+.5,z:node.p.z+.5}); d.spawnParticle("minecraft:basic_smoke_particle",{x:node.p.x+.5,y:node.p.y+.5,z:node.p.z+.5}); } } }); }
world.afterEvents.playerBreakBlock.subscribe(e=>{ queueIntegrity(e.dimension,e.block.location); const typeId = e.brokenBlockPermutation.type.id; if (typeId.endsWith("_log")) fellTree(e.dimension, e.block.location, typeId); });

async function showMachine(player, block) { const m=ensureMachine(block); const wind=windFor(block.dimension,block.location); const form=new ActionFormData().title(block.typeId === "omni:coal_turbine" ? "Coal Turbine Control" : "Transformer Substation").body(`§7Temperature: §f${m.temperature.toFixed(1)} K\n§7Health: §f${m.health.toFixed(1)}%\n§7Output: §f${m.outputKW.toFixed(1)} kW\n§7Delivered load: §f${m.deliveredKW.toFixed(1)} kW\n§7Grid state: ${m.brownout ? "§cBrownout" : "§aBalanced"}\n§7Regional wind: §f${(wind.speed*100).toFixed(0)}%`).button(m.online ? "Disable machine" : "Enable machine"); if(m.type==="omni:coal_turbine") form.button("Load 120 coal units"); const response=await form.show(player); if(response.canceled) return; if(response.selection===0) m.online=!m.online; if(response.selection===1 && m.type==="omni:coal_turbine") m.fuel+=120; persistMachineBlock(block, m); save(); }
world.afterEvents.playerInteractWithBlock.subscribe(e=>{ if(MACHINES.has(e.block.typeId)) showMachine(e.player,e.block); });
world.afterEvents.playerPlaceBlock.subscribe(e=>{ if(MACHINES.has(e.block.typeId)) ensureMachine(e.block); });
world.afterEvents.entitySpawn.subscribe(e=>{ if(e.entity.typeId === "minecraft:tnt") { const p=e.entity.location; queueIntegrity(e.entity.dimension,p); } });

load();
system.runInterval(()=>{ for(const d of [world.getDimension("overworld"),world.getDimension("nether"),world.getDimension("the_end")]) solveGrid(d); save(); },GRID_PERIOD);
system.runInterval(()=>{ for(const d of [world.getDimension("overworld"),world.getDimension("nether"),world.getDimension("the_end")]) thermalTick(d); },THERMAL_PERIOD);
system.runInterval(structureTick,STRUCTURE_PERIOD);
system.runInterval(()=>{ for(const p of world.getAllPlayers()) windFor(p.dimension,p.location); },100);
