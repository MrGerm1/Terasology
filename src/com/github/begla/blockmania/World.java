/*
 *  Copyright 2011 Benjamin Glatzel <benjamin.glatzel@me.com>.
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *  under the License.
 */
package com.github.begla.blockmania;

import com.github.begla.blockmania.blocks.Block;
import com.github.begla.blockmania.generators.ChunkGenerator;
import com.github.begla.blockmania.generators.ChunkGeneratorForest;
import com.github.begla.blockmania.generators.ChunkGeneratorFlora;
import com.github.begla.blockmania.generators.ChunkGeneratorTerrain;
import com.github.begla.blockmania.generators.ObjectGeneratorPineTree;
import com.github.begla.blockmania.generators.ObjectGeneratorTree;
import com.github.begla.blockmania.utilities.FastRandom;
import java.io.IOException;
import static org.lwjgl.opengl.GL11.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.lwjgl.util.vector.Vector3f;
import org.newdawn.slick.opengl.Texture;
import org.newdawn.slick.opengl.TextureLoader;
import org.newdawn.slick.util.ResourceLoader;

/**
 * The world of Blockmania. At its most basic the world contains chunks (consisting of a fixed amount of blocks)
 * and the player.
 *
 * The world is randomly generated by using some perlin noise generators initialized
 * with a favored seed value.
 * 
 * @author Benjamin Glatzel <benjamin.glatzel@me.com>
 */
public final class World extends RenderableObject {

    private int _statGeneratedChunks = 0;
    private double _statUpdateDuration = 0.0f;
    /* ------ */
    private short _time = 8;
    private long lastDaytimeMeasurement = Helper.getInstance().getTime();
    /* ------ */
    private static Texture _textureSun, _textureMoon;
    /* ------ */
    private byte _daylight = 16;
    private Player _player;
    /* ------ */
    private boolean _updatingEnabled = false;
    private boolean _updateThreadAlive = true;
    private final Thread _updateThread;
    /* ------ */
    private final List<Chunk> _chunkUpdateQueueDL = new LinkedList<Chunk>();
    private final List<Chunk> _chunkUpdateNormal = new LinkedList<Chunk>();
    private final Map<Integer, Chunk> _chunkCache = new TreeMap<Integer, Chunk>();
    /* ------ */
    private final ChunkGeneratorTerrain _generatorTerrain;
    private final ChunkGeneratorForest _generatorForest;
    private final ChunkGeneratorFlora _generatorGrass;
    private final ObjectGeneratorTree _generatorTree;
    private final ObjectGeneratorPineTree _generatorPineTree;
    private final FastRandom _rand;
    /* ------ */
    private String _title, _seed;
    /* ----- */
    int _lastGeneratedChunkID = 0;
    /* ----- */
    private ArrayList<Chunk> _visibleChunks = new ArrayList<Chunk>();
    private long _lastWorldUpdate = Helper.getInstance().getTime();

    /**
     * Initializes a new world for the single player mode.
     * 
     * @param title The title/description of the world
     * @param seed The seed string used to genrate the terrain
     * @param p The player
     */
    public World(String title, String seed, Player p) {
        this._player = p;

        _title = title;
        _seed = seed;

        // Generate a random name for the world if the name is not set
        if (_title.equals("")) {
            _title = seed;
        }

        // Init. generators
        _generatorTerrain = new ChunkGeneratorTerrain(seed);
        _generatorForest = new ChunkGeneratorForest(seed);
        _generatorTree = new ObjectGeneratorTree(this, seed);
        _generatorPineTree = new ObjectGeneratorPineTree(this, seed);
        _generatorGrass = new ChunkGeneratorFlora(seed);

        // Init. random generator
        _rand = new FastRandom(seed.hashCode());

        _updateThread = new Thread(new Runnable() {

            @Override
            public void run() {
                while (true) {
                    if (!_updateThreadAlive) {
                        return;
                    }

                    if (!_updatingEnabled) {
                        synchronized (_updateThread) {
                            try {
                                _updateThread.wait();
                            } catch (InterruptedException ex) {
                            }
                        }
                    }

                    long timeStart = System.currentTimeMillis();
                    timeStart = System.currentTimeMillis();

                    if (!_chunkUpdateNormal.isEmpty()) {
                        Chunk[] chunks = _chunkUpdateNormal.toArray(new Chunk[0]);

                        // Find the nearest chunk
                        // >>>
                        double dist = Float.MAX_VALUE;
                        int index = -1;

                        for (int i = 0; i < chunks.length; i++) {
                            Chunk c = chunks[i];
                            double tDist = c.calcDistanceToPlayer();

                            if (tDist <= dist) {
                                dist = tDist;
                                index = i;
                            }
                        }
                        // <<<

                        if (index >= 0) {
                            Chunk c = (Chunk) chunks[index];
                            processChunk(c);
                        }
                        _statUpdateDuration += System.currentTimeMillis() - timeStart;
                        _statUpdateDuration /= 2;
                    }

                    updateDaytime();
                    evolveChunks();

                    // Update the visible chunks each second
                    if (Helper.getInstance().getTime() - _lastWorldUpdate > 1000) {
                        _visibleChunks = fetchVisibleChunks();
                        _lastWorldUpdate = Helper.getInstance().getTime();
                    }
                   
                    // HACK: Reduce CPU usage a little bit
                    try {
                        Thread.sleep(15);
                    } catch (InterruptedException ex) {
                        Logger.getLogger(World.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            }
        });
    }

    /**
     * Stops the updating thread and writes all chunks to disk.
     */
    public void dispose() {
        synchronized (_updateThread) {
            _updateThreadAlive = false;
            _updateThread.notify();

        }
        writeAllChunksToDisk();
    }

    /**
     * Processes a chunk. This method is used within the update thread
     * and updates the lighting and vertex arrays of a chunk and its
     * neighbors based on their dirty flags.
     *
     * @param c The chunk to process
     */
    private void processChunk(Chunk c) {
        if (c != null) {

            // Only process visible chunks
            if (!_visibleChunks.contains(c)) {
                _chunkUpdateNormal.remove(c);
                return;
            }

            c.generate();
            Chunk[] neighbors = c.loadOrCreateNeighbors();

            /*
             * Before the light is flooded, make sure that the neighbor chunks
             * are present and generated.
             */
            for (Chunk nc : neighbors) {
                if (nc != null) {
                    nc.generate();

                }
            }

            /*
             * Now flood light and propagate into adjacent chunks.
             */
            if (c.isLightDirty()) {
                c.updateLight();
            }

            for (Chunk nc : neighbors) {
                if (nc != null) {
                    /*
                     * If a neighbor chunk was changed
                     * queue it for updating.
                     */
                    if (nc.isDirty() && isChunkVisible(nc)) {
                        queueChunkForUpdate(nc);
                    }
                }
            }

            if (c.isDirty()) {
                // Only generate the vertex arrays and display lists of visible chunks
                if (isChunkVisible(c)) {
                    c.generateVertexArrays();
                    _chunkUpdateQueueDL.add(c);
                }
            }

            _chunkUpdateNormal.remove(c);
        }

        _statGeneratedChunks++;
    }

    /**
     * Updates the time of the world. A day in Blockmania takes 12 minutes.
     */
    private void updateDaytime() {
        if (Helper.getInstance().getTime() - lastDaytimeMeasurement >= 30000) {
            if (_chunkUpdateNormal.isEmpty()) {
                _time = (short) ((_time + 1) % 24);
            } else {
                return;
            }
            lastDaytimeMeasurement = Helper.getInstance().getTime();

            Logger.getLogger(World.class.getName()).log(Level.INFO, "Updated daytime to {0}h.", _time);

            byte oldDaylight = _daylight;

            if (_time >= 18 && _time < 20) {
                _daylight = (byte) (0.8f * Configuration.MAX_LIGHT);
            } else if (_time == 20) {
                _daylight = (byte) (0.6f * Configuration.MAX_LIGHT);
            } else if (_time == 21) {
                _daylight = (byte) (0.4f * Configuration.MAX_LIGHT);
            } else if (_time == 22 || _time == 23) {
                _daylight = (byte) (0.3f * Configuration.MAX_LIGHT);
            } else if (_time >= 0 && _time <= 5) {
                _daylight = (byte) (0.2f * Configuration.MAX_LIGHT);
            } else if (_time == 6) {
                _daylight = (byte) (0.3f * Configuration.MAX_LIGHT);
            } else if (_time == 7) {
                _daylight = (byte) (0.6f * Configuration.MAX_LIGHT);
            } else if (_time >= 8 && _time < 18) {
                _daylight = (byte) Configuration.MAX_LIGHT;
            }

            // Only update the chunks if the daylight value has changed
            if (_daylight != oldDaylight) {
                markCachedChunksDirty();
                updateAllChunks();
            }
        }
    }

    /**
     * 
     */
    private void evolveChunks() {
        if (!_chunkUpdateNormal.isEmpty() || _visibleChunks.isEmpty()) {
            return;
        }

        Chunk c = _visibleChunks.get((int) (Math.abs(_rand.randomLong()) % _visibleChunks.size()));

        if (!c.isFresh()) {
            _generatorGrass.generate(c);
            queueChunkForUpdate(c);
        }
    }

    /**
     * Queues all displayed chunks for updating.
     */
    public void updateAllChunks() {
        for (Chunk c : _visibleChunks) {
            queueChunkForUpdate(c);
        }
    }

    /**
     * Static initialization of a chunk. For example, this
     * method loads the texture of the sun.
     */
    public static void init() {
        try {
            Logger.getLogger(World.class.getName()).log(Level.INFO, "Loading world textures...");
            _textureSun = TextureLoader.getTexture("png", ResourceLoader.getResource("com/github/begla/blockmania/images/sun.png").openStream(), GL_NEAREST);
            _textureMoon = TextureLoader.getTexture("png", ResourceLoader.getResource("com/github/begla/blockmania/images/moon.png").openStream(), GL_NEAREST);
            Logger.getLogger(World.class.getName()).log(Level.INFO, "Finished loading world textures!");
        } catch (IOException ex) {
            Logger.getLogger(World.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * Renders the world.
     */
    @Override
    public void render() {
        renderHorizon();
        renderChunks();
    }

    /**
     * Renders the horizon.
     */
    public void renderHorizon() {
        glPushMatrix();
        // Position the sun relatively to the player
        glTranslatef(_player.getPosition().x, Configuration.CHUNK_DIMENSIONS.y * 1.25f, Configuration.VIEWING_DISTANCE_IN_CHUNKS.y * Configuration.CHUNK_DIMENSIONS.z + _player.getPosition().z);

        // Disable fog
        glDisable(GL_FOG);

        glColor4f(1f, 1f, 1f, 1.0f);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glEnable(GL_TEXTURE_2D);

        if (isDaytime()) {
            _textureSun.bind();
        } else {
            _textureMoon.bind();
        }
        glBegin(GL_QUADS);
        glTexCoord2f(0.0f, 0.0f);
        glVertex3f(-Configuration.SUN_SIZE, Configuration.SUN_SIZE, -Configuration.SUN_SIZE);
        glTexCoord2f(1.f, 0.0f);
        glVertex3f(Configuration.SUN_SIZE, Configuration.SUN_SIZE, -Configuration.SUN_SIZE);
        glTexCoord2f(1.f, 1.0f);
        glVertex3f(Configuration.SUN_SIZE, -Configuration.SUN_SIZE, -Configuration.SUN_SIZE);
        glTexCoord2f(0.f, 1.0f);
        glVertex3f(-Configuration.SUN_SIZE, -Configuration.SUN_SIZE, -Configuration.SUN_SIZE);
        glEnd();
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_BLEND);

        glEnable(GL_FOG);
        glPopMatrix();
    }

    /**
     * 
     * @return 
     */
    public ArrayList<Chunk> fetchVisibleChunks() {
        ArrayList<Chunk> visibleChunks = new ArrayList<Chunk>();

        for (int x = -((int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.x / 2); x < ((int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.x / 2); x++) {
            for (int z = -((int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.y / 2); z < ((int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.y / 2); z++) {
                Chunk c = loadOrCreateChunk(calcPlayerChunkOffsetX() + x, calcPlayerChunkOffsetZ() + z);
                if (c != null) {
                    // If this chunk is fresh, queue it for updates
                    if (c.isFresh() || c.isDirty() || c.isLightDirty()) {
                        queueChunkForUpdate(c);
                    }
                    visibleChunks.add(c);
                }
            }
        }

        return visibleChunks;
    }

    /**
     * Renders all active chunks.
     */
    public void renderChunks() {
        for (Chunk c : _visibleChunks) {
            c.render(false);
        }
        for (Chunk c : _visibleChunks) {
            c.render(true);
        }
    }

    /*
     * Updates the world. This method checks the queue for the display
     * list updates and recreates the display lists accordingly.
     */
    @Override
    public void update(long delta) {
        try {
            Chunk c = _chunkUpdateQueueDL.remove(0);
            c.generateDisplayLists();
        } catch (Exception e) {
        }
    }

    /**
     * Returns the chunk position of a given coordinate.
     *
     * @param x The X-coordinate of the block
     * @return The X-coordinate of the chunk
     */
    private int calcChunkPosX(int x) {
        return (x / (int) Configuration.CHUNK_DIMENSIONS.x);
    }

    /**
     * Returns the chunk position of a given coordinate.
     *
     * @param z The Z-coordinate of the block
     * @return The Z-coordinate of the chunk
     */
    private int calcChunkPosZ(int z) {
        return (z / (int) Configuration.CHUNK_DIMENSIONS.z);
    }

    /**
     * Returns the internal position of a block within a chunk.
     *
     * @param x1 The X-coordinate of the block within the world
     * @param x2 The X-coordinate of the chunk within the world
     * @return The X-coordinate of the block within the chunk
     */
    private int calcBlockPosX(int x1, int x2) {
        x1 = x1 % ((int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.x * (int) Configuration.CHUNK_DIMENSIONS.x);
        return (x1 - (x2 * (int) Configuration.CHUNK_DIMENSIONS.x));
    }

    /**
     * Returns the internal position of a block within a chunk.
     *
     * @param x1 The Z-coordinate of the block within the world
     * @param x2 The Z-coordinate of the chunk within the world
     * @return The Z-coordinate of the block within the chunk
     */
    private int calcBlockPosZ(int z1, int z2) {
        z1 = z1 % ((int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.y * (int) Configuration.CHUNK_DIMENSIONS.z);
        return (z1 - (z2 * (int) Configuration.CHUNK_DIMENSIONS.z));
    }

    /**
     * Places a block of a specific type at a given position.
     * 
     * @param x The X-coordinate
     * @param y The Y-coordinate
     * @param z The Z-coordinate
     * @param type The type of the block to set
     * @param update If set the affected chunk is queued for updating
     * @param overwrite  
     */
    public final void setBlock(int x, int y, int z, byte type, boolean update, boolean overwrite) {
        int chunkPosX = calcChunkPosX(x) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.x;
        int chunkPosZ = calcChunkPosZ(z) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.y;

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        try {
            Chunk c = loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));

            if (overwrite || c.getBlock(blockPosX, y, blockPosZ) == 0) {
                c.setBlock(blockPosX, y, blockPosZ, type);

                if (update) {
                    byte oldValue = getLight(x, y, z);
                    c.calcSunlightAtLocalPos(blockPosX, blockPosZ, true);
                    c.refreshLightAtLocalPos(blockPosX, y, blockPosZ);
                    byte newValue = getLight(x, y, z);

                    if (newValue > oldValue) {
                        c.spreadLight(blockPosX, y, blockPosZ, newValue);
                    } else if (newValue < oldValue) {
                        //c.unspreadLight(blockPosX, y, blockPosZ, oldValue);
                    }
                    queueChunkForUpdate(c);
                }
            }
        } catch (Exception e) {
        }
    }

    /**
     * 
     * @param pos
     * @return 
     */
    public final byte getBlockAtPosition(Vector3f pos) {
        return getBlock((int) (pos.x + 0.5f), (int) (pos.y + 0.5f), (int) (pos.z + 0.5f));
    }

    /**
     * Returns the block at the given position.
     *
     * @param x The X-coordinate
     * @param y The Y-coordinate
     * @param z The Z-coordinate
     * @return The type of the block
     */
    public final byte getBlock(int x, int y, int z) {
        int chunkPosX = calcChunkPosX(x) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.x;
        int chunkPosZ = calcChunkPosZ(z) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.y;

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        try {
            Chunk c = loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));
            return c.getBlock(blockPosX, y, blockPosZ);
        } catch (Exception e) {
        }

        return -1;
    }

    /**
     * 
     * @param x
     * @param y
     * @param z
     * @return 
     */
    public final boolean canBlockSeeTheSky(int x, int y, int z) {
        int chunkPosX = calcChunkPosX(x) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.x;
        int chunkPosZ = calcChunkPosZ(z) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.y;

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        try {
            Chunk c = loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));
            return c.canBlockSeeTheSky(blockPosX, y, blockPosZ);
        } catch (Exception e) {
        }

        return false;
    }

    /**
     * Returns the light value at the given position.
     *
     * @param x The X-coordinate
     * @param y The Y-coordinate
     * @param z The Z-coordinate
     * @return The light value
     */
    public final byte getLight(int x, int y, int z) {
        int chunkPosX = calcChunkPosX(x) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.x;
        int chunkPosZ = calcChunkPosZ(z) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.y;

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        try {
            Chunk c = loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));
            return c.getLight(blockPosX, y, blockPosZ);
        } catch (Exception e) {
        }

        return -1;
    }

    /**
     * Sets the light value at the given position.
     *
     * @param x The X-coordinate
     * @param y The Y-coordinate
     * @param z The Z-coordinate
     * @param intens The light intensity value
     */
    public void setSunlight(int x, int y, int z, byte intens) {
        int chunkPosX = calcChunkPosX(x) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.x;
        int chunkPosZ = calcChunkPosZ(z) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.y;

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        try {
            Chunk c = loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));
            c.setSunlight(blockPosX, y, blockPosZ, intens);
        } catch (Exception e) {
        }
    }

    /**
     * Recursive light calculation.
     * 
     * Too slow!
     * 
     * @param x
     * @param y
     * @param z
     * @param lightValue
     * @param depth  
     */
    public void spreadLight(int x, int y, int z, byte lightValue, int depth) {
        int chunkPosX = calcChunkPosX(x) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.x;
        int chunkPosZ = calcChunkPosZ(z) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.y;

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        try {
            Chunk c = loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));
            c.spreadLight(blockPosX, y, blockPosZ, lightValue, depth);
        } catch (Exception e) {
        }
    }

    /**
     * Recursive light calculation.
     * 
     * Too weird.
     * 
     * @param x
     * @param y
     * @param z
     * @param oldValue
     * @param depth  
     */
    public void unspreadLight(int x, int y, int z, byte oldValue, int depth) {
        int chunkPosX = calcChunkPosX(x) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.x;
        int chunkPosZ = calcChunkPosZ(z) % (int) Configuration.VIEWING_DISTANCE_IN_CHUNKS.y;

        int blockPosX = calcBlockPosX(x, chunkPosX);
        int blockPosZ = calcBlockPosZ(z, chunkPosZ);

        try {
            Chunk c = loadOrCreateChunk(calcChunkPosX(x), calcChunkPosZ(z));
            c.unspreadLight(blockPosX, y, blockPosZ, oldValue, depth);
        } catch (Exception e) {
        }
    }

    /**
     * Returns the daylight value.
     * 
     * @return The daylight value
     */
    public float getDaylightAsFloat() {
        return _daylight / 16f;
    }

    /**
     * Returns the player.
     * 
     * @return The player
     */
    public Player getPlayer() {
        return _player;
    }

    /**
     * Returns the color of the daylight as a vector.
     * 
     * @return The daylight color
     */
    public Vector3f getDaylightColor() {
        return new Vector3f(getDaylightAsFloat() * 0.55f, getDaylightAsFloat() * 0.85f, 0.99f * getDaylightAsFloat());
    }

    /**
     * Calculates the offset of the player relative to the spawning point.
     * 
     * @return The player offset on the x-axis
     */
    private int calcPlayerChunkOffsetX() {
        return (int) (_player.getPosition().x / Configuration.CHUNK_DIMENSIONS.x);
    }

    /**
     * Calculates the offset of the player relative to the spawning point.
     *
     * @return The player offset on the z-axis
     */
    private int calcPlayerChunkOffsetZ() {
        return (int) (_player.getPosition().z / Configuration.CHUNK_DIMENSIONS.z);
    }

    /**
     * Returns the vertices of a block at the given position.
     *
     * @param x
     * @param y
     * @param z
     * @return
     */
    public Vector3f[] verticesForBlockAt(int x, int y, int z) {
        Vector3f[] vertices = new Vector3f[8];

        vertices[0] = new Vector3f(x - .5f, y - .5f, z - .5f);
        vertices[1] = new Vector3f(x + .5f, y - .5f, z - .5f);
        vertices[2] = new Vector3f(x + .5f, y + .5f, z - .5f);
        vertices[3] = new Vector3f(x - .5f, y + .5f, z - .5f);

        vertices[4] = new Vector3f(x - .5f, y - .5f, z + .5f);
        vertices[5] = new Vector3f(x + .5f, y - .5f, z + .5f);
        vertices[6] = new Vector3f(x + .5f, y + .5f, z + .5f);
        vertices[7] = new Vector3f(x - .5f, y + .5f, z + .5f);

        return vertices;
    }

    /**
     * Calculates the intersection of a given ray originating from a specified point with
     * a block. Returns a list of intersections ordered by the distance to the player.
     *
     * @param x
     * @param y
     * @param z
     * @param origin
     * @param ray
     * @return Distance-ordered list of ray-face-intersections
     */
    public ArrayList<RayFaceIntersection> rayBlockIntersection(int x, int y, int z, Vector3f origin, Vector3f ray) {
        /*
         * Ignore invisible blocks.
         */
        if (Block.getBlockForType(getBlock(x, y, z)).isBlockInvisible()) {
            return null;
        }

        ArrayList<RayFaceIntersection> result = new ArrayList<RayFaceIntersection>();

        /*
         * Fetch all vertices of the specified block.
         */
        Vector3f[] vertices = verticesForBlockAt(x, y, z);
        Vector3f blockPos = new Vector3f(x, y, z);

        /*
         * Generate a new intersection for each side of the block.
         */

        // Front
        RayFaceIntersection is = rayFaceIntersection(blockPos, vertices[0], vertices[3], vertices[2], origin, ray);
        if (is != null) {
            result.add(is);
        }

        // Back
        is = rayFaceIntersection(blockPos, vertices[4], vertices[5], vertices[6], origin, ray);
        if (is != null) {
            result.add(is);
        }

        // Left
        is = rayFaceIntersection(blockPos, vertices[0], vertices[4], vertices[7], origin, ray);
        if (is != null) {
            result.add(is);
        }

        // Right
        is = rayFaceIntersection(blockPos, vertices[1], vertices[2], vertices[6], origin, ray);
        if (is != null) {
            result.add(is);
        }

        // Top
        is = rayFaceIntersection(blockPos, vertices[3], vertices[7], vertices[6], origin, ray);
        if (is != null) {
            result.add(is);
        }

        // Bottom
        is = rayFaceIntersection(blockPos, vertices[0], vertices[1], vertices[5], origin, ray);
        if (is != null) {
            result.add(is);
        }

        /*
         * Sort the intersections by distance.
         */
        Collections.sort(result);
        return result;
    }

    /**
     * Calculates a intersection with the face of a block defined by 3 points.
     * 
     * @param blockPos The position of the block to intersect with
     * @param v0 Point 1
     * @param v1 Point 2
     * @param v2 Point 3
     * @param origin Origin of the intersection ray
     * @param ray Direction of the intersection ray
     * @return Ray-face-intersection
     */
    private RayFaceIntersection rayFaceIntersection(Vector3f blockPos, Vector3f v0, Vector3f v1, Vector3f v2, Vector3f origin, Vector3f ray) {

        //Calculate the plane to intersect with.
        Vector3f a = Vector3f.sub(v1, v0, null);
        Vector3f b = Vector3f.sub(v2, v0, null);
        Vector3f norm = Vector3f.cross(a, b, null);


        float d = -(norm.x * v0.x + norm.y * v0.y + norm.z * v0.z);

        /**
         * Calculate the distance on the ray, where the intersection occurs.
         */
        float t = -(norm.x * origin.x + norm.y * origin.y + norm.z * origin.z + d) / (Vector3f.dot(ray, norm));

        if (t < 0) {
            return null;
        }

        /**
         * Calc. the point of intersection.
         */
        Vector3f intersectPoint = new Vector3f(ray.x * t, ray.y * t, ray.z * t);
        Vector3f.add(intersectPoint, origin, intersectPoint);

        if (intersectPoint.x >= v0.x && intersectPoint.x <= v2.x && intersectPoint.y >= v0.y && intersectPoint.y <= v2.y && intersectPoint.z >= v0.z && intersectPoint.z <= v2.z) {
            return new RayFaceIntersection(blockPos, v0, v1, v2, d, t, origin, ray, intersectPoint);
        }

        return null;
    }

    /**
     * Loads a specified chunk from the cache or queues a new chunk for
     * generation.
     *
     * NOTE: This method ALWAYS returns a valid chunk since new chunks
     * are generated if none of the present chunks fit.
     *
     * 
     * @param x X-coordinate of the chunk
     * @param z Z-coordinate of the chunk
     * @return The chunk
     */
    public Chunk loadOrCreateChunk(int x, int z) {
        // Catch negative values
        if (x < 0 || z < 0) {
            return null;
        }

        // Try to load the chunk from the cache
        Chunk c = _chunkCache.get(Helper.getInstance().cantorize(x, z));

        // We got a chunk! Already! Great!
        if (c != null) {
            return c;
        } else {
            // Looks a like a new chunk has to be created from scratch
        }


        // Okay we have a full cache here. Alert!
        if (_chunkCache.size() >= 1024) {
            // Fetch all chunks within the cache
            ArrayList<Chunk> sortedChunks = null;
            sortedChunks = new ArrayList<Chunk>(_chunkCache.values());
            // Sort them according to their distance to the player
            Collections.sort(sortedChunks);

            Logger.getLogger(this.getClass().getName()).log(Level.INFO, "Cache full. Removing some chunks from the chunk cache...");

            // Free some space
            for (int i = 0; i < 32; i++) {
                int indexToDelete = sortedChunks.size() - i;

                if (indexToDelete >= 0 && indexToDelete < sortedChunks.size()) {
                    Chunk cc = sortedChunks.get(indexToDelete);
                    // Save the chunk before removing it from the cache
                    _chunkCache.remove(Helper.getInstance().cantorize((int) cc.getPosition().x, (int) cc.getPosition().z));
                    _chunkUpdateNormal.remove(cc);
                    cc.dispose();
                }
            }

            Logger.getLogger(this.getClass().getName()).log(Level.INFO, "Finished removing chunks from chunk cache.");
        }

        // Init a new chunk
        c = prepareNewChunk(x, z);
        _chunkCache.put(Helper.getInstance().cantorize(x, z), c);

        return c;
    }

    /**
     * Marks the chunks stored within the chunk cache as dirty. If a chunk is dirty,
     * the vertex arrays are recreated the next time the chunk is queued for updating.
     *
     * @param markLightDirty If true the light will be recomputated
     */
    private void markCachedChunksDirty() {
        for (Chunk c : _chunkCache.values()) {
            c.setDirty(true);
        }
    }

    /**
     * Returns true if the given chunk is present in the cache.
     * 
     * @param c The chunk
     * @return True if the chunk is present in the chunk cache
     */
    public boolean isChunkCached(Chunk c) {
        return loadChunk((int) c.getPosition().x, (int) c.getPosition().z) != null;
    }

    /**
     * Tries to load a chunk from the cache. Returns null if no
     * chunk is found.
     * 
     * @param x X-coordinate
     * @param z Z-coordinate
     * @return The loaded chunk
     */
    public Chunk loadChunk(int x, int z) {
        Chunk c = _chunkCache.get(Helper.getInstance().cantorize(x, z));
        return c;
    }

    private void queueChunkForUpdate(Chunk c) {
        if (!_chunkUpdateNormal.contains(c)) {
            _chunkUpdateNormal.add(c);
        }
    }

    /**
     * Displays some information about the world formatted as a string.
     * 
     * @return String with world information
     */
    @Override
    public String toString() {
        return String.format("world (cdl: %d, cn: %d, cache: %d, ud: %fs, seed: \"%s\", title: \"%s\")", _chunkUpdateQueueDL.size(), _chunkUpdateNormal.size(), _chunkCache.size(), _statUpdateDuration / 1000d, _seed, _title);
    }

    /**
     * Starts the updating thread.
     */
    public void startUpdateThread() {
        _updatingEnabled = true;
        _updateThread.start();
    }

    /**
     * Resumes the updating thread.
     */
    public void resumeUpdateThread() {
        _updatingEnabled = true;
        synchronized (_updateThread) {
            _updateThread.notify();
        }
    }

    /**
     * Safely suspends the updating thread.
     */
    public void suspendUpdateThread() {
        _updatingEnabled = false;
    }

    /**
     * Sets the time of the world.
     *
     * @param time The time to set
     */
    public void setTime(short time) {
        _time = (short) (time % 24);
    }

    /**
     *
     * @return
     */
    public ObjectGeneratorPineTree getGeneratorPineTree() {
        return _generatorPineTree;
    }

    /**
     *
     * @return
     */
    public ObjectGeneratorTree getGeneratorTree() {
        return _generatorTree;
    }

    /**
     * Returns true if it is daytime.
     * @return
     */
    public boolean isDaytime() {
        if (_time > 6 && _time < 20) {
            return true;
        }
        return false;
    }

    /**
     * Returns true if it is nighttime.
     * 
     * @return
     */
    public boolean isNighttime() {
        return !isDaytime();
    }

    /**
     * Sets the title of the world.
     * 
     * @param _title The title of the world
     */
    public void setTitle(String _title) {
        this._title = _title;
    }

    /**
     * Returns the title of the world.
     * 
     * @return The title of the world
     */
    public String getTitle() {
        return _title;
    }

    /**
     * Writes all chunks to the disk.
     */
    public void writeAllChunksToDisk() {
        for (Chunk c : _chunkCache.values()) {
            c.writeChunkToDisk();
        }
    }

    /**
     * TODO
     * @param x
     * @param z  
     */
    public void generateNewChunk(int x, int z) {
        Chunk c = loadOrCreateChunk(x, z);
        c.generate();

        if (c == null) {
            return;
        }

        Chunk[] neighbors = c.loadOrCreateNeighbors();

        for (Chunk nc : neighbors) {
            if (nc != null) {
                nc.generate();
            }
        }

        c.updateLight();
        c.writeChunkToDisk();
    }

    /**
     * 
     * @param x
     * @param z
     * @return 
     */
    private Chunk prepareNewChunk(int x, int z) {
        ArrayList<ChunkGenerator> gs = new ArrayList<ChunkGenerator>();
        gs.add(_generatorTerrain);
        gs.add(_generatorForest);

        // Generate a new chunk, cache it and return it
        Chunk c = new Chunk(this, new Vector3f(x, 0, z), gs);
        return c;
    }

    /**
     * 
     * @param c
     * @return
     */
    public boolean isChunkVisible(Chunk c) {
        if (c.getPosition().x >= calcChunkPosX((int) _player.getPosition().x) - Configuration.VIEWING_DISTANCE_IN_CHUNKS.x / 2 && c.getPosition().x < Configuration.VIEWING_DISTANCE_IN_CHUNKS.x / 2 + calcChunkPosX((int) _player.getPosition().x)) {
            if (c.getPosition().z >= calcChunkPosZ((int) _player.getPosition().z) - Configuration.VIEWING_DISTANCE_IN_CHUNKS.y / 2 && c.getPosition().z < Configuration.VIEWING_DISTANCE_IN_CHUNKS.y / 2 + calcChunkPosZ((int) _player.getPosition().z)) {

                return true;
            }
        }
        return false;
    }

    /**
     * 
     */
    public void printPlayerChunkPosition() {
        int chunkPosX = calcChunkPosX((int) _player.getPosition().x);
        int chunkPosZ = calcChunkPosX((int) _player.getPosition().z);
        System.out.println(_chunkCache.get(Helper.getInstance().cantorize(chunkPosX, chunkPosZ)));
    }

    /**
     * 
     */
    public int getStatGeneratedChunks() {
        return _statGeneratedChunks;
    }
}
