package nl.enjarai.client_paintings;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Streams;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.entity.decoration.painting.PaintingVariant;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceReloader;
import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.registry.Registry;
import nl.enjarai.client_paintings.util.Vec2i;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class ClientPaintingManager implements IdentifiableResourceReloadListener {
    public static final Identifier SPRITE_ATLAS_ID = ClientPaintings.id("textures/atlas/client_paintings.png");
    public static final Identifier PAINTING_BACK_ID = new Identifier("painting/back");

    public final HashMap<Vec2i, List<PaintingVariant>> defaultPaintings;

    public final Map<Identifier, ClientPainting> paintings = Maps.newHashMap();
    public SpriteAtlasTexture spriteAtlas;

    public ClientPaintingManager() {
        defaultPaintings = new HashMap<>();
        Registry.PAINTING_VARIANT.stream().forEach(paintingVariant -> {
            Vec2i size = new Vec2i(paintingVariant.getWidth(), paintingVariant.getHeight());
            if (!defaultPaintings.containsKey(size)) {
                defaultPaintings.put(size, Lists.newArrayList());
            }
            defaultPaintings.get(size).add(paintingVariant);
        });
    }

    @Nullable
    public ClientPainting getPaintingFromUUID(UUID uuid, int sizeX, int sizeY) {
        if (paintings.isEmpty()) return null;

        var matching = paintings.values().stream()
                .filter(clientPainting -> clientPainting.getPixelsX() == sizeX && clientPainting.getPixelsY() == sizeY)
                .toList();

        if (matching.isEmpty()) {
            return null;
        }

        var index = Math.abs(uuid.hashCode()) % (matching.size() + defaultPaintings.get(new Vec2i(sizeX, sizeY)).size());
        if (index < matching.size()) {
            return matching.get(index);
        } else {
            return null;
        }
    }

    private SpriteAtlasTexture getSpriteAtlas() {
        if (spriteAtlas == null) {
            spriteAtlas = new SpriteAtlasTexture(SPRITE_ATLAS_ID);
            MinecraftClient.getInstance().getTextureManager().registerTexture(spriteAtlas.getId(), spriteAtlas);
        }
        return spriteAtlas;
    }


    @Override
    public Identifier getFabricId() {
        return ClientPaintings.id("client_paintings");
    }

    protected void loadJson(ResourceManager manager, Identifier id, Resource resource, Map<Identifier, ClientPainting> paintings) {
        try (Reader reader = resource.getReader()) {
            var painting = JsonHelper.deserialize(reader).getAsJsonObject();
            var paintingTexture = new Identifier(painting.get("texture").getAsString());
            var paintingBackTexture = painting.has("back") ? new Identifier(painting.get("back").getAsString()) : null;
            var paintingSize = painting.get("size").getAsJsonArray();
            var paintingSizeX = paintingSize.get(0).getAsInt();
            var paintingSizeY = paintingSize.get(1).getAsInt();
            paintings.put(id, new ClientPainting(paintingTexture, paintingBackTexture, paintingSizeX, paintingSizeY));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public CompletableFuture<Void> reload(ResourceReloader.Synchronizer synchronizer, ResourceManager manager, Profiler prepareProfiler, Profiler applyProfiler, Executor prepareExecutor, Executor applyExecutor) {
        getSpriteAtlas();

        Map<Identifier, ClientPainting> paintings = Maps.newConcurrentMap();
        var paintingsFuture = CompletableFuture.supplyAsync(() -> {
            return CompletableFuture.allOf(manager.findResources("client_paintings", (path) -> path.getPath().endsWith(".json")).entrySet().stream().map(entry -> {
                var id = entry.getKey();
                var resource = entry.getValue();
                return CompletableFuture.runAsync(() -> loadJson(manager, id, resource, paintings), prepareExecutor);
            }).toArray(CompletableFuture[]::new)).join();
        }, prepareExecutor);

        var stitchingFuture = paintingsFuture.thenApplyAsync((v) -> {
            prepareProfiler.startTick();
            prepareProfiler.push("stitching");
            var paintingSprites = Streams.concat(
                    paintings.values().stream().map(ClientPainting::getTexture),
                    paintings.values().stream().map(ClientPainting::getBackTexture)
            );
            SpriteAtlasTexture.Data data = getSpriteAtlas().stitch(manager, paintingSprites, prepareProfiler, 0);
            prepareProfiler.pop();
            prepareProfiler.endTick();
            return data;
        }, prepareExecutor);

        var uploadFuture = stitchingFuture.thenCompose(synchronizer::whenPrepared).thenAcceptAsync((data) -> {
            applyProfiler.startTick();
            applyProfiler.push("upload");
            getSpriteAtlas().upload(data);
            applyProfiler.pop();
            applyProfiler.endTick();
        }, applyExecutor);

        return uploadFuture.thenRunAsync(() -> {
            this.paintings.clear();
            this.paintings.putAll(paintings);
            ClientPaintings.LOGGER.info("Loaded " + paintings.size() + " client paintings");
        }, applyExecutor);
    }

    public class ClientPainting {

        private final Identifier texture;
        @Nullable
        private final Identifier backTexture;
        private final int sizeX;
        private final int sizeY;

        public ClientPainting(Identifier texture, @Nullable Identifier backTexture, int sizeX, int sizeY) {
            this.texture = texture;
            this.backTexture = backTexture;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
        }

        public Identifier getId() {
            return paintings.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(this))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Painting not loaded properly"))
                    .getKey();
        }

        public Identifier getTexture() {
            return texture;
        }

        public Identifier getBackTexture() {
            return backTexture != null ? backTexture : PAINTING_BACK_ID;
        }

        public Sprite getSprite() {
            return getSpriteAtlas().getSprite(getTexture());
        }

        public Sprite getBackSprite() {
            return getSpriteAtlas().getSprite(getBackTexture());
        }

        public int getSizeX() {
            return sizeX;
        }

        public int getSizeY() {
            return sizeY;
        }

        public int getPixelsX() {
            return sizeX * 16;
        }

        public int getPixelsY() {
            return sizeY * 16;
        }
    }
}
