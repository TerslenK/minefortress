package org.minefortress.network.c2s;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.minefortress.network.interfaces.FortressC2SPacket;
import org.minefortress.professions.ServerProfessionManager;

public class ServerboundChangeProfessionStatePacket implements FortressC2SPacket {

    private final String professionId;
    private final AmountChange amountChange;

    public ServerboundChangeProfessionStatePacket(String professionId, AmountChange amountChange) {
        this.professionId = professionId;
        this.amountChange = amountChange;
    }

    public ServerboundChangeProfessionStatePacket(PacketByteBuf buf) {
        this.professionId = buf.readString();
        this.amountChange = AmountChange.values()[buf.readInt()];
    }

    @Override
    public void write(PacketByteBuf buf) {
        buf.writeString(professionId);
        buf.writeInt(amountChange.ordinal());
    }

    @Override
    public void handle(MinecraftServer server, ServerPlayerEntity player) {
        final ServerProfessionManager manager = this.getFortressServerManager(server, player).getServerProfessionManager();
        if (amountChange == AmountChange.ADD) {
            if(ServerProfessionManager.HIREABLE_PROFESSIONS.contains(professionId)) {
                manager.openHireMenu(professionId, player);
            } else {
                manager.increaseAmount(professionId);
            }
        } else {
            manager.decreaseAmount(professionId);
        }
    }

    public enum AmountChange {
        ADD,
        REMOVE
    }

}
