package il.co.codeguru.corewars8086.war;

import java.util.EventListener;

/**
 * Interface for warrior event listeners
 */
public interface WarriorEventListener extends EventListener {
    /**
     * Called when a new warrior enters the arena.
     *
     * @param warriorName The warrior's name.
     */
    void onWarriorBirth(String warriorName);

    /**
     * Called when a warrior dies.
     *
     * @param warriorName The warrior's name.
     * @param reason      The warrior's death reason.
     */
    void onWarriorDeath(String warriorName, String reason);
}
