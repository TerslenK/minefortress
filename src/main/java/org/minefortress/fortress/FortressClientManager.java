package org.minefortress.fortress;

import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.border.WorldBorder;
import org.jetbrains.annotations.NotNull;
import org.minefortress.MineFortressMod;
import org.minefortress.blueprints.manager.BlueprintMetadata;
import org.minefortress.blueprints.manager.ClientBlueprintManager;
import org.minefortress.entity.BasePawnEntity;
import org.minefortress.fight.ClientFightManager;
import org.minefortress.fortress.automation.EssentialBuildingInfo;
import org.minefortress.fortress.resources.client.ClientResourceManager;
import org.minefortress.fortress.resources.client.ClientResourceManagerImpl;
import org.minefortress.interfaces.FortressMinecraftClient;
import org.minefortress.network.c2s.ServerboundFortressCenterSetPacket;
import org.minefortress.network.c2s.ServerboundSetGamemodePacket;
import org.minefortress.network.helpers.FortressChannelNames;
import org.minefortress.network.helpers.FortressClientNetworkHelper;
import org.minefortress.professions.ClientProfessionManager;
import org.minefortress.utils.BlockUtils;
import org.minefortress.utils.BuildingHelper;
import org.minefortress.utils.ModUtils;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public final class FortressClientManager extends AbstractFortressManager {

    private static final Object KEY = new Object();
    private static final int FORTRESS_BORDER_SIZE = 64;

    private final ClientProfessionManager professionManager;
    private final ClientResourceManager resourceManager = new ClientResourceManagerImpl();
    private final ClientFightManager fightManager = new ClientFightManager();

    private boolean connectedToTheServer = false;
    private boolean initialized = false;

    private BlockPos fortressCenter = null;
    private int colonistsCount = 0;
    private int reservedColonistCount = 0;

    private EssentialBuildingInfo hoveredBuilding = null;

    private volatile FortressToast setCenterToast;

    private BlockPos posAppropriateForCenter;
    private BlockPos oldPosAppropriateForCenter;

    private BasePawnEntity selectedPawn;
    private Vec3d selectedColonistDelta;

    private List<EssentialBuildingInfo> buildings = new ArrayList<>();
    private Map<Block, List<BlockPos>> specialBlocks = new HashMap<>();
    private Map<Block, List<BlockPos>> blueprintsSpecialBlocks = new HashMap<>();

    private FortressGamemode gamemode;

    private int maxColonistsCount;

    private FortressState state = FortressState.BUILD;

    public FortressClientManager() {
        professionManager = new ClientProfessionManager(() -> ((FortressMinecraftClient) MinecraftClient.getInstance()).getFortressClientManager());
    }

    public void select(BasePawnEntity colonist) {
        if(state == FortressState.COMBAT) {
            final var mouse = MinecraftClient.getInstance().mouse;
            final var selectionManager = fightManager.getSelectionManager();
            selectionManager.startSelection(mouse.getX(), mouse.getY(), colonist.getPos());
            selectionManager.updateSelection(mouse.getX(), mouse.getY(), colonist.getPos());
            selectionManager.endSelection();

            selectedPawn = null;
            return;
        }
        this.selectedPawn = colonist;
        final Vec3d entityPos = colonist.getPos();
        final Vec3d playerPos = ModUtils.getClientPlayer().getPos();

        selectedColonistDelta = entityPos.subtract(playerPos);
    }

    public void updateBuildings(List<EssentialBuildingInfo> buildings) {
        this.buildings = buildings;
    }

    public void setSpecialBlocks(Map<Block, List<BlockPos>> specialBlocks, Map<Block, List<BlockPos>> blueprintSpecialBlocks) {
        this.specialBlocks = specialBlocks;
        this.blueprintsSpecialBlocks = blueprintSpecialBlocks;
    }

    public Optional<WorldBorder> getFortressBorder() {
        if(isCenterNotSet()) {
            return getWorldBorder(posAppropriateForCenter);
        }
        return Optional.ofNullable(fortressCenter).flatMap(FortressClientManager::getWorldBorder);
    }

    @NotNull
    private static Optional<WorldBorder> getWorldBorder(BlockPos pos) {
        if(pos != null) {
            final WorldBorder border = new WorldBorder();
            final var x = pos.getX();
            final var z = pos.getZ();
            final var xSign = Math.signum(x);
            final var zSign = Math.signum(z);
            final var nonZeroSignX = xSign == 0 ? 1 : xSign;
            final var nonZeroSignZ = zSign == 0 ? 1 : zSign;
            final var adjustedX = x - x % FORTRESS_BORDER_SIZE + nonZeroSignX * FORTRESS_BORDER_SIZE / 2f;
            final var adjustedZ = z - z % FORTRESS_BORDER_SIZE + nonZeroSignZ * FORTRESS_BORDER_SIZE / 2f;
            border.setCenter(adjustedX, adjustedZ);
            border.setSize(FORTRESS_BORDER_SIZE);
            return Optional.of(border);
        } else {
            return Optional.empty();
        }
    }

    public boolean isSelectingColonist() {
        return selectedPawn != null && state == FortressState.BUILD;
    }

    public BasePawnEntity getSelectedPawn() {
        return selectedPawn;
    }

    public void stopSelectingColonist() {
        this.selectedPawn = null;
        this.selectedColonistDelta = null;
    }

    public Vec3d getProperCameraPosition() {
        if(!isSelectingColonist()) throw new IllegalStateException("No colonist selected");
        return this.selectedPawn.getPos().subtract(selectedColonistDelta);
    }

    @Override
    public int getReservedPawnsCount() {
        return reservedColonistCount;
    }

    public void sync(int colonistsCount, BlockPos fortressCenter, FortressGamemode gamemode, boolean connectedToTheServer, int maxColonistsCount, int reservedColonistCount) {
        this.colonistsCount = colonistsCount;
        this.fortressCenter = fortressCenter;
        this.gamemode = gamemode;
        this.connectedToTheServer = connectedToTheServer;
        this.maxColonistsCount = maxColonistsCount;
        this.reservedColonistCount = reservedColonistCount;
        this.initialized = true;
    }

    public void tick(FortressMinecraftClient fortressClient) {
        if(isSelectingColonist() && selectedPawn.isDead()) stopSelectingColonist();

        final MinecraftClient client = (MinecraftClient) fortressClient;
        if(
                client.world == null ||
                client.interactionManager == null ||
                client.interactionManager.getCurrentGameMode() != MineFortressMod.FORTRESS
        ) {
            synchronized (KEY) {
                if(setCenterToast != null) {
                    setCenterToast.hide();
                    setCenterToast = null;
                }
            }

            posAppropriateForCenter = null;
            return;
        }
        if(!initialized) return;
        if(isCenterNotSet()) {
            synchronized (KEY) {
                if(setCenterToast == null) {
                    this.setCenterToast = new FortressToast("Set up your Fortress", "Right-click to place", Items.CAMPFIRE);
                    client.getToastManager().add(setCenterToast);
                }
            }

            final BlockPos hoveredBlockPos = fortressClient.getHoveredBlockPos();
            if(hoveredBlockPos!=null && !hoveredBlockPos.equals(BlockPos.ORIGIN)) {
                if(hoveredBlockPos.equals(oldPosAppropriateForCenter)) return;

                BlockPos cursor = hoveredBlockPos;
                while (!BuildingHelper.canPlaceBlock(client.world, cursor))
                    cursor = cursor.up();

                while (BuildingHelper.canPlaceBlock(client.world, cursor.down()))
                    cursor = cursor.down();

                posAppropriateForCenter = cursor.toImmutable();
            }
        }
    }

    public boolean isConnectedToTheServer() {
        return initialized && connectedToTheServer;
    }

    public BlockPos getPosAppropriateForCenter() {
        return posAppropriateForCenter;
    }

    public boolean notInitialized() {
        return !initialized;
    }

    public boolean isCenterNotSet() {
        return initialized && fortressCenter == null && this.gamemode != FortressGamemode.NONE;
    }

    public void setupFortressCenter() {
        if(fortressCenter!=null) throw new IllegalStateException("Fortress center already set");
        this.setCenterToast.hide();
        this.setCenterToast = null;
        fortressCenter = posAppropriateForCenter;
        posAppropriateForCenter = null;
        final ServerboundFortressCenterSetPacket serverboundFortressCenterSetPacket = new ServerboundFortressCenterSetPacket(fortressCenter);
        FortressClientNetworkHelper.send(FortressChannelNames.FORTRESS_SET_CENTER, serverboundFortressCenterSetPacket);

        final MinecraftClient client = MinecraftClient.getInstance();
        final WorldRenderer worldRenderer = client.worldRenderer;


        if(worldRenderer!=null) {
            worldRenderer.scheduleBlockRenders(fortressCenter.getX(), fortressCenter.getY(), fortressCenter.getZ());
            worldRenderer.scheduleTerrainUpdate();
        }
    }

    public void updateRenderer(WorldRenderer worldRenderer) {
        if(oldPosAppropriateForCenter == posAppropriateForCenter) return;
        final BlockPos posAppropriateForCenter = this.getPosAppropriateForCenter();
        if(posAppropriateForCenter != null) {
            oldPosAppropriateForCenter = posAppropriateForCenter;
            final BlockPos start = posAppropriateForCenter.add(-2, -2, -2);
            final BlockPos end = posAppropriateForCenter.add(2, 2, 2);
            worldRenderer.scheduleBlockRenders(start.getX(), start.getY(), start.getZ(), end.getX(), end.getY(), end.getZ());
            worldRenderer.scheduleTerrainUpdate();
        }
    }

    public List<BlockPos> getBuildingSelection(BlockPos pos) {
        for(EssentialBuildingInfo building : buildings){
            final BlockPos start = building.getStart();
            final BlockPos end = building.getEnd();
            if(BlockUtils.isPosBetween(pos, start, end)){
                hoveredBuilding = building;
                return StreamSupport
                        .stream(BlockPos.iterate(start, end).spliterator(), false)
                        .map(BlockPos::toImmutable)
                        .collect(Collectors.toList());
            }
        }
        hoveredBuilding = null;
        return Collections.emptyList();
    }

    public boolean isBuildingSelected() {
        return hoveredBuilding != null;
    }

    public EssentialBuildingInfo getHoveredBuilding() {
        return hoveredBuilding;
    }
    public Optional<String> getHoveredBuildingName() {
        return Optional.ofNullable(hoveredBuilding)
                .map(it -> ModUtils.getBlueprintManager())
                .map(ClientBlueprintManager::getBlueprintMetadataManager)
                .flatMap(it -> it.getByRequirementId(hoveredBuilding.getRequirementId()))
                .map(BlueprintMetadata::getName);
    }

    public ClientProfessionManager getProfessionManager() {
        return professionManager;
    }

    @Override
    public boolean hasRequiredBuilding(String requirementId, int minCount) {
        final var reuiredBuilding = buildings.stream()
                .filter(b -> b.getRequirementId().equals(requirementId));
        if(requirementId.startsWith("miner") || requirementId.startsWith("lumberjack") || requirementId.startsWith("warrior")) {
            return reuiredBuilding
                    .mapToLong(it -> it.getBedsCount() * 10)
                    .sum() > minCount;
        }
        final var count = reuiredBuilding.count();
        if(requirementId.equals("shooting_gallery"))
            return count * 10 > minCount;

        if(requirementId.startsWith("farm"))
            return count * 5 > minCount;


        return count > minCount;
    }

    public int countBuildings(String requirementId) {
        return (int) buildings.stream()
                .filter(b -> b.getRequirementId().equals(requirementId))
                .count();
    }

    @Override
    public boolean hasRequiredBlock(Block block, boolean blueprint, int minCount) {
        if(blueprint)
            return this.blueprintsSpecialBlocks.getOrDefault(block, Collections.emptyList()).size() > minCount;
        else
            return this.specialBlocks.getOrDefault(block, Collections.emptyList()).size() > minCount;
    }

    @Override
    public int getTotalColonistsCount() {
        return colonistsCount;
    }

    @Override
    public void setGamemode(FortressGamemode gamemode) {
        if(gamemode == null) throw new IllegalArgumentException("Gamemode cannot be null");
        if(gamemode == FortressGamemode.NONE) throw new IllegalArgumentException("Gamemode cannot be NONE");
        final ServerboundSetGamemodePacket serverboundSetGamemodePacket = new ServerboundSetGamemodePacket(gamemode);
        FortressClientNetworkHelper.send(FortressChannelNames.FORTRESS_SET_GAMEMODE, serverboundSetGamemodePacket);
    }

    public boolean gamemodeNeedsInitialization() {
        return this.initialized && this.gamemode == FortressGamemode.NONE;
    }

    public boolean isCreative() {
        return this.gamemode == FortressGamemode.CREATIVE;
    }

    public boolean isSurvival() {
        return this.gamemode != null && this.gamemode == FortressGamemode.SURVIVAL;
    }

    public ClientResourceManager getResourceManager() {
        return resourceManager;
    }

    public ClientFightManager getFightManager() {
        return fightManager;
    }

    public int getMaxColonistsCount() {
        return maxColonistsCount;
    }

    public void reset() {
        this.initialized = false;
        this.selectedPawn = null;
        this.selectedColonistDelta = null;
    }

    // getter and setter for state
    public void setState(FortressState state) {
        this.state = state;
        if(state == FortressState.AREAS_SELECTION) {
            ModUtils.getAreasClientManager().getSavedAreasHolder().setNeedRebuild(true);
        }
        if(state == FortressState.BUILD) {
            ModUtils.getClientTasksHolder().ifPresent(it -> it.setNeedRebuild(true));
        }
    }

    public FortressState getState() {
        return this.state;
    }

}
