package dev.ledgerlab.outbound;

/** execute performs external I/O; applyInTransaction must perform only synchronous database work. */
public interface CommandHandler {
    String kind();

    CommandResult execute(CommandSnapshot command, CommandExecution execution);

    void applyInTransaction(CommandSnapshot command, CommandResult result);
}
