package github.freshchromatic.catbud_addons.island_assistive;

import github.freshchromatic.catbud_addons.mining_study.StudyPacket;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

record StudyPayload(StudyPacket packet) implements CustomPacketPayload {

    static final Type<StudyPayload> TYPE = new Type<>(Identifier.parse(StudyPacket.CHANNEL));
    static final StreamCodec<RegistryFriendlyByteBuf, StudyPayload> CODEC = new StreamCodec<>() {

        @Override
        public StudyPayload decode(RegistryFriendlyByteBuf buffer) {
            if (buffer.readableBytes() > 8192) {
                throw new IllegalArgumentException("Study payload too large");
            }
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return new StudyPayload(StudyPacket.decode(bytes));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, StudyPayload payload) {
            buffer.writeBytes(payload.packet.encode());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
