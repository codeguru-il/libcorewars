package il.co.codeguru.corewars8086.war;

import il.co.codeguru.corewars8086.cpu.CpuException;
import il.co.codeguru.corewars8086.memory.MemoryEventListener;
import il.co.codeguru.corewars8086.memory.MemoryException;
import il.co.codeguru.corewars8086.memory.RealModeAddress;
import il.co.codeguru.corewars8086.memory.RealModeMemoryImpl;
import il.co.codeguru.corewars8086.utils.Unsigned;

import java.util.*;

/**
 * Implements the main "war-logic".
 *
 * @author DL
 */
public class War {
    /**
     * Arena's code segment
     */
    public final static short ARENA_SEGMENT = 0x1000;
    /**
     * Arena's size in bytes (= size of a single segment)
     */
    public final static int ARENA_SIZE = RealModeAddress.PARAGRAPHS_IN_SEGMENT * RealModeAddress.PARAGRAPH_SIZE;
    /**
     * Warrior's private stack size
     */
    private final static short STACK_SIZE = 2 * 1024;
    /**
     * Group-shared private memory size
     */
    private final static short GROUP_SHARED_MEMORY_SIZE = 1024;
    /**
     * Arena is filled with this byte
     */
    private final static byte ARENA_BYTE = (byte) 0xCC;
    /**
     * Maximum number of warriors in a fight
     */
    private final static int MAX_WARRIORS = 20;
    /**
     * Maximum attempts to load a warrior to the Arena
     */
    private final static int MAX_LOADING_TRIES = 100;
    /**
     * Minimum initial space (in bytes) between loaded warriors
     */
    private final static int MIN_GAP = 1024;

    /**
     * Warriors in the fight
     */
    private final Warrior[] m_warriors;
    /**
     * Number of loaded warriors
     */
    private int m_numWarriors;
    /**
     * Number of warriors still alive
     */
    private int m_numWarriorsAlive;
    /**
     * Addresses equal or larger than this are still unused.
     * An address can be 'used' either by the Arena, or by the private stacks.
     */
    private int m_nextFreeAddress;
    /**
     * The 'physical' memory core
     */
    private final RealModeMemoryImpl m_core;

    /**
     * The number of the current warrior
     */
    private int m_currentWarrior;

    /**
     * The listener for warrior events
     */
    private final WarriorEventListener m_warListener;

    private final Random rand = new Random();

    private boolean isSingleRound;
    private boolean isPaused;

    public void setSeed(long seed) {
        rand.setSeed(seed);
    }

    public void pause() {
        isPaused = true;
    }

    public boolean isPaused() {
        return isPaused;
    }

    public void resume() {
        isPaused = false;
        isSingleRound = false;
    }

    public void runSingleRound() {
        this.resume();
        isSingleRound = true;
    }

    public boolean isSingleRound() {
        return this.isSingleRound;
    }

    public RealModeMemoryImpl getMemory() {
        return m_core;
    }

    private final int zombieSpeed;

    /**
     * Constructor.
     * <p>
     * Fills the Arena with its initial data.
     */
    public War(MemoryEventListener memoryListener, WarriorEventListener warListener, boolean startPaused, int zombieSpeed) {
        isPaused = startPaused;
        m_warListener = warListener;
        m_warriors = new Warrior[MAX_WARRIORS];
        m_numWarriors = 0;
        m_numWarriorsAlive = 0;
        m_core = new RealModeMemoryImpl();
        m_nextFreeAddress = RealModeAddress.PARAGRAPH_SIZE * (ARENA_SEGMENT + RealModeAddress.PARAGRAPHS_IN_SEGMENT);

        // initialize arena
        for (int offset = 0; offset < ARENA_SIZE; offset++) {
            RealModeAddress tmp = new RealModeAddress(ARENA_SEGMENT, (short) offset);
            m_core.writeByte(tmp, ARENA_BYTE);
        }

        isSingleRound = false;

        // set the memory listener (we only do this now, to skip initialization)
        m_core.setListener(memoryListener);

        this.zombieSpeed = zombieSpeed;
    }

    /**
     * Loads the given warrior groups to the Arena.
     *
     * @param warriorGroups The warrior groups to load.
     */
    public void loadWarriorGroups(WarriorGroup[] warriorGroups) throws Exception {
        m_currentWarrior = 0;
        ArrayList<WarriorGroup> groupsLeftToLoad = new ArrayList<>(Arrays.asList(warriorGroups));

        while (!groupsLeftToLoad.isEmpty()) {
            int randomInt = rand.nextInt(groupsLeftToLoad.size());
            loadWarriorGroup(groupsLeftToLoad.get(randomInt));
            groupsLeftToLoad.remove(randomInt);
        }
    }

    /**
     * Runs a single round of the war (every living warrior does his turn).
     *
     * @param round The current round number.
     */
    public void nextRound(int round) {
        for (int i = 0; i < m_numWarriors; ++i) {
            Warrior warrior = m_warriors[i];
            m_currentWarrior = i;
            if (warrior.isAlive()) {
                try {
                    // run first opcode
                    warrior.nextOpcode();

                    if (warrior.isZombie()) {
                        // Zombie H runs at double speed
                        int remainingRounds = (this.zombieSpeed * ((warrior.getType() == WarriorType.ZOMBIE_H) ? 2 : 1)) - 1;

                        for (int j = 0; j < remainingRounds; j++) {
                            warrior.nextOpcode();
                        }
                    } else {  // run one extra opcode, if warrior deserves it :)
                        updateWarriorEnergy(warrior, round);
                        if (shouldRunExtraOpcode(warrior)) {
                            warrior.nextOpcode();
                        }
                    }
                } catch (CpuException e) {
                    m_warListener.onWarriorDeath(warrior.getName(), "CPU exception");
                    warrior.kill();
                    --m_numWarriorsAlive;
                } catch (MemoryException e) {
                    m_warListener.onWarriorDeath(warrior.getName(), "memory exception");
                    warrior.kill();
                    --m_numWarriorsAlive;
                }
            }
        }
    }

    /**
     * @return whether the War is over.
     */
    public boolean isOver() {
        return (m_numWarriorsAlive < 2);
    }

    /**
     * Decrements the warrior's Energy value, if the current round is
     * a multiple of DECELERATION_ROUNDS.
     *
     * @param warrior The warrior.
     * @param round   Current round number.
     */
    private void updateWarriorEnergy(Warrior warrior, int round) {
        if ((round % DECELERATION_ROUNDS) == 0) {
            int energy = Unsigned.unsignedShort(warrior.getEnergy());

            if (energy > 0) {
                warrior.setEnergy((short) (energy - 1));
            }
        }
    }

    /**
     * Determines whether or not a given warrior deserves an extra opcode,
     * by calculating the warrior's current speed (using its current Energy
     * value), and comparing it against a random value.
     * <p>
     * We use a random-based algorithm (as opposed to a deterministic one) for
     * the following reasons:
     * a) simple implementation - there is no need to keep record of past
     * decisions as our algorithm is stateless.
     * b) we want the warrior's speed to vary between x1.0 to x2.0, and this
     * solves the issue of determining what to do if the current speed is x1.7 :)
     *
     * @param warrior The warrior.
     * @return true if the warrior deserves an extra opcode, otherwise
     * returns false.
     */
    private boolean shouldRunExtraOpcode(Warrior warrior) {
        int energy = Unsigned.unsignedShort(warrior.getEnergy());
        int speed = calculateWarriorSpeed(energy);

        return (rand.nextInt(MAX_SPEED) < speed);
    }

    /**
     * Maximum possible Warrior speed.
     */
    private final int MAX_SPEED = 16; // when Energy = 0xFFFF
    /**
     * Warrior's Energy is decremented every so often rounds.
     */
    private final int DECELERATION_ROUNDS = 5;

    /**
     * Returns the warrior's current speed, using the following formula:
     * Speed := Min(MAX_SPEED, 1+Log2(Energy))
     * <p>
     * This formula forces the warrior to put more and more effort in order to
     * increase its speed, i.e. non-linear effort.
     *
     * @param energy The warrior's Energy value.
     * @return the warrior's current speed,
     */
    private int calculateWarriorSpeed(int energy) {
        if (energy == 0) {
            return 0;
        } else {
            return Math.min(MAX_SPEED, 1 + (int) (Math.log(energy) / Math.log(2)));
        }
    }

    private void loadWarriorGroup(WarriorGroup warriorGroup) throws Exception {
        List<WarriorData> warriors = warriorGroup.getWarriors();

        RealModeAddress groupSharedMemory = allocateCoreMemory(GROUP_SHARED_MEMORY_SIZE);

        for (WarriorData warrior : warriors) {

            String warriorName = warrior.getName();
            byte[] warriorData = warrior.getCode();

            short loadOffset = getLoadOffset(warriorData.length);

            RealModeAddress loadAddress = new RealModeAddress(ARENA_SEGMENT, loadOffset);
            RealModeAddress stackMemory = allocateCoreMemory(STACK_SIZE);
            RealModeAddress initialStack = new RealModeAddress(stackMemory.getSegment(), STACK_SIZE);

            m_warriors[m_numWarriors++] = new Warrior(warriorName,
                    warriorData.length,
                    m_core,
                    loadAddress,
                    initialStack,
                    groupSharedMemory,
                    GROUP_SHARED_MEMORY_SIZE,
                    warrior.getType());

            // load warrior to arena
            for (int offset = 0; offset < warriorData.length; ++offset) {
                RealModeAddress tmp = new RealModeAddress(ARENA_SEGMENT, (short) (loadOffset + offset));
                m_core.writeByte(tmp, warriorData[offset]);
            }
            ++m_numWarriorsAlive;
            ++m_currentWarrior;

            // notify listener
            m_warListener.onWarriorBirth(warriorName);
        }
    }

    /**
     * Virtually allocates core memory of a given size, by advancing the
     * next-free-memory pointer (m_nextFreeAddress).
     *
     * @param size Required memory size, must be a multiple of
     *             RealModeAddress.PARAGRAPH_SIZE
     * @return Pointer to the beginning of the allocated memory block.
     */
    private RealModeAddress allocateCoreMemory(short size) throws Exception {
        if ((size % RealModeAddress.PARAGRAPH_SIZE) != 0) {
            throw new Exception();
        }

        RealModeAddress allocatedMemory = new RealModeAddress(m_nextFreeAddress);

        m_nextFreeAddress += size;

        return allocatedMemory;
    }

    /**
     * Returns a suitable random address to which a warrior with a given code
     * size can be loaded.
     * <p>
     * A suitable address is-
     * 1. far enough from the Arena's boundaries.
     * 2. far enough from other loaded warriors.
     *
     * @param warriorSize Code size of the loaded warrior.
     * @return offset within the Arena to which the warrior can be loaded.
     * @throws Exception if no suitable address could be found.
     */
    private short getLoadOffset(int warriorSize) throws Exception {
        int loadAddress = 0;
        boolean found = false;
        int numTries = 0;

        while ((!found) && (numTries < MAX_LOADING_TRIES)) {
            ++numTries;

            loadAddress = rand.nextInt(ARENA_SIZE);

            found = loadAddress >= MIN_GAP;

            if (loadAddress + warriorSize > ARENA_SIZE - MIN_GAP) {
                found = false;
            }

            for (int i = 0; i < m_numWarriors; ++i) {
                int otherLoadAddress = Unsigned.unsignedShort(m_warriors[i].getLoadOffset());
                int otherSize = m_warriors[i].getCodeSize();

                int otherStart = otherLoadAddress - MIN_GAP;
                int otherEnd = otherLoadAddress + otherSize + MIN_GAP;

                if ((loadAddress + warriorSize >= otherStart) && (loadAddress < otherEnd)) {
                    found = false;
                }
            }
        }

        if (!found) {
            throw new Exception();
        }

        return (short) loadAddress;
    }

    /**
     * @return Returns the currentWarrior.
     */
    public int getCurrentWarrior() {
        return m_currentWarrior;
    }

    /**
     * @return the warrior with the given index
     */
    public Warrior getWarrior(int index) {
        return m_warriors[index];
    }

    /**
     * @return the numebr of warriors fighting in this match.
     */
    public int getNumWarriors() {
        return m_numWarriors;
    }

    /**
     * @return the number of warriors still alive.
     */
    public int getNumRemainingWarriors() {
        return m_numWarriorsAlive;
    }

    /**
     * @return a comma-seperated list of all warriors still alive.
     */
    public String getRemainingWarriorNames() {
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < m_numWarriors; ++i) {
            Warrior warrior = m_warriors[i];
            if (warrior.isAlive()) {
                if (names.toString().isEmpty()) {
                    names = new StringBuilder(warrior.getName());
                } else {
                    names.append(", ").append(warrior.getName());
                }
            }
        }
        return names.toString();
    }

    /**
     * Calculates the scores for this war. Do not call if the war is still running (see {@link War#isOver()}). Each war
     * is allocated one point, which is divided equally among the living warriors.
     *
     * @return A {@link Map} whose keys are the living warriors' names, and the values are the point values for each
     * living warrior.
     */
    public Map<String, Float> getScores() {
        float scorePerWarrior = (float) 1.0 / m_numWarriorsAlive;
        Map<String, Float> scores = new HashMap<>();

        for (int i = 0; i < m_numWarriors; ++i) {
            Warrior warrior = m_warriors[i];
            if (warrior.isAlive()) {
                scores.put(warrior.getName(), scorePerWarrior);
            }
        }

        return scores;
    }
}
