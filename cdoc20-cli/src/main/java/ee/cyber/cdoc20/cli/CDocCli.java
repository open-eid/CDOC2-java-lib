package ee.cyber.cdoc20.cli;

import ee.cyber.cdoc20.cli.commands.CDocCreateCmd;
import ee.cyber.cdoc20.cli.commands.CDocDecryptCmd;
import ee.cyber.cdoc20.cli.commands.CDocInfoCmd;
import ee.cyber.cdoc20.cli.commands.CDocListCmd;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

//S106 Standard outputs should not be used directly to log anything
//CLI needs to interact with standard outputs
@SuppressWarnings("java:S106")
@Command(
        version = {"cdoc20-cli version: 0.0.1", "cdoc20-lib version: 0.0.1"},
        name = "cdoc20-cli",
        header = "\r\ncdoc20-cli is a command line interface for cdoc20 library\r\n",
        customSynopsis = { "cdoc [create] <arguments>",
                "cdoc [decrypt] <arguments>",
                "cdoc [list] <arguments>",
                "cdoc [info] <arguments>"},
        subcommands = {CDocCreateCmd.class,
                CDocDecryptCmd.class,
                CDocListCmd.class,
                CDocInfoCmd.class}
)
public class CDocCli implements Callable<Void> {
    @Option(names = {"--version"}, versionHelp = true, description = "display version info")
    boolean versionInfoRequested;

    @Option(names = { "-h", "--help" }, usageHelp = true, description = "display a help message")
    boolean helpRequested = false;

    public static void main(String... args) {
        if (args.length == 0) {
            CommandLine.usage(new CDocCli(), System.out);
            CommandLine.usage(new CDocCreateCmd(), System.out);
            CommandLine.usage(new CDocDecryptCmd(), System.out);
            CommandLine.usage(new CDocListCmd(), System.out);
            CommandLine.usage(new CDocInfoCmd(), System.out);
        }
        int exitCode = new CommandLine(new CDocCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Void call() {
        return null;
    }
}
