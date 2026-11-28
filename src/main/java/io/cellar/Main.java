package io.cellar;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public final class Main {
    public static final String VERSION="3.2.0";
    private Main() {}
    public static void main(String[] args) {int code=run(args);if(code!=0)System.exit(code);}
    public static int run(String[] args) {
        Map<String,String> options=new LinkedHashMap<>();String command=args.length==0?"web":args[0];
        try {
            if(command.equals("--version")){System.out.println("Cellar Guard "+VERSION);return 0;}
            if(command.equals("--help")||command.equals("help")){help();return 0;}
            Set<String> flags=Set.of("--advisory","--summary","--record","--demo-break");
            Set<String> allowed=switch(command){
                case "check" -> Set.of("--config","--csv","--format","--output","--advisory","--summary","--record","--data");
                case "diff" -> Set.of("--config","--baseline","--csv","--format","--output","--summary");
                case "history" -> Set.of("--config","--data");
                case "web" -> Set.of("--data","--port");
                case "desktop" -> Set.of();
                case "audit-verify" -> Set.of("--data");
                case "validate","init" -> Set.of("--config");
                case "export-demo" -> Set.of("--out","--demo-break");
                default -> throw new InputError("Unknown command. Run --help.");
            };
            for(int i=1;i<args.length;i++) {
                String key=args[i];if(!allowed.contains(key)||options.containsKey(key))throw new InputError("Unknown or repeated option: "+key);
                if(flags.contains(key))options.put(key,"true");
                else {if(i+1>=args.length||args[i+1].startsWith("--"))throw new InputError("Missing value for "+key);options.put(key,args[++i]);}
            }
            Path config=Path.of(options.getOrDefault("--config","cellar.json"));
            Path data=Path.of(options.getOrDefault("--data",config.toAbsolutePath().getParent().resolve(".cellar").toString()));
            Store store=new Store(config,data);
            switch(command) {
                case "init" -> {
                    if(Files.exists(config))throw new InputError("Contract already exists; init will not overwrite it.");
                    Path parent=config.toAbsolutePath().getParent();Files.createDirectories(parent);
                    Files.writeString(config,Demo.CONFIG,StandardOpenOption.CREATE_NEW);
                    System.out.println("Created "+config);return 0;
                }
                case "export-demo" -> {
                    Path out=Path.of(required(options,"--out"));FilesEx.atomicWrite(out,Demo.csv(options.containsKey("--demo-break")));
                    System.out.println("Generated synthetic export: "+out);return 0;
                }
                case "validate" -> {
                    Contract c=store.contract();
                    System.out.println("VALID: "+c.integrations().size()+" integrations; row rules="+c.hasRowRules()
                        +(c.validateRows()?"; validateRows=true":""));
                    return 0;
                }
                case "history" -> {System.out.println(Json.write(store.history()));return 0;}
                case "desktop" -> {
                    io.cellar.desktop.DesktopApp.main(new String[0]);
                    return 0;
                }
                case "web" -> {
                    int port=-1;
                    if(options.containsKey("--port")){try{port=Integer.parseInt(options.get("--port"));}catch(NumberFormatException e){throw new InputError("Port must be an integer.");}if(port<0||port>65535)throw new InputError("Port must be 0–65535.");}
                    io.cellar.web.WebConfig wc=io.cellar.web.WebConfig.fromEnv(System.getenv(),port,options.containsKey("--data")?Path.of(options.get("--data")):null);
                    io.cellar.web.WebServer server=null;
                    for(int attempt=0;server==null;attempt++){
                        try{server=new io.cellar.web.WebServer(wc);}
                        catch(java.net.BindException busy){
                            if(port>=0||wc.shared()||System.getenv("CELLAR_PORT")!=null||attempt>=10||wc.port()==65535)
                                throw new InputError("Port "+wc.port()+" is already in use. Close the other program or choose a different port.");
                            System.out.println("Port "+wc.port()+" is busy, trying "+(wc.port()+1)+"...");
                            wc=new io.cellar.web.WebConfig(wc.bindHost(),wc.port()+1,wc.dataDir(),wc.publicOrigin(),wc.trustProxy(),wc.tlsKeystore(),wc.tlsPassword(),wc.masterKeyEnv(),wc.accessKey());
                        }
                    }
                    server.start();Runtime.getRuntime().addShutdownHook(new Thread(server::close));
                    String scheme=wc.tls()?"https":"http";
                    System.out.println("Cellar Guard "+VERSION+" listening on "+scheme+"://"+wc.bindHost()+":"+server.port()+(wc.publicOrigin()!=null?"  (public: "+wc.publicOrigin()+")":""));
                    System.out.println(wc.accessKey()==null?"Open the address in your browser. No sign-in needed (localhost only).":"Access key required (CELLAR_ACCESS_KEY).");
                    new CountDownLatch(1).await();return 0;
                }
                case "audit-verify" -> {
                    Path dir=Path.of(options.getOrDefault("--data",System.getenv().getOrDefault("CELLAR_DATA","data")));
                    io.cellar.web.Crypto crypto=io.cellar.web.Crypto.load(System.getenv("CELLAR_MASTER_KEY"),dir.toAbsolutePath().resolve("master.key"));
                    io.cellar.web.Audit audit=new io.cellar.web.Audit(new io.cellar.web.Vault(dir,crypto),crypto);var v=audit.verify();
                    System.out.println((v.ok()?"OK  ":"BROKEN  ")+v.entries()+" entries; head="+v.head()+(v.ok()?"":" ; "+v.problem()));return v.ok()?0:1;
                }
                default -> {
                    String format=options.getOrDefault("--format","text");
                    if(!Set.of("text","json","markdown").contains(format))throw new InputError("Format must be text, json, or markdown.");
                    Path csvPath=Path.of(required(options,"--csv"));Path baseline=null;
                    if(command.equals("diff"))baseline=Path.of(required(options,"--baseline"));
                    if(options.containsKey("--output")) {
                        Path out=Path.of(options.get("--output"));
                        for(Path input:baseline==null?List.of(config,csvPath):List.of(config,csvPath,baseline))
                            if(out.toAbsolutePath().normalize().equals(input.toAbsolutePath().normalize()) || (Files.exists(out)&&Files.isSameFile(out,input)))
                                throw new InputError("Report output must not overwrite an input file.");
                    }
                    Contract c=store.contract();String csv=FilesEx.read(csvPath);
                    Map<String,Object> result=command.equals("diff")?Checker.diff(c,FilesEx.read(baseline),csv):Checker.check(c,csv,options.containsKey("--advisory"));
                    String rendered=switch(format){case "json"->Json.write(result)+"\n";case "markdown"->Reports.markdown(result);default->Reports.text(result);};
                    if(options.containsKey("--record"))store.record(result);
                    if(options.containsKey("--output"))FilesEx.atomicWrite(Path.of(options.get("--output")),rendered);
                    if(options.containsKey("--summary"))summary(Reports.markdown(result));
                    System.out.print(rendered);return Boolean.TRUE.equals(result.get("blocking"))?1:0;
                }
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();return 2;}
        catch(Exception e) {
            String message=(e instanceof InputError||e instanceof Store.Conflict)?e.getMessage():"Operation failed. Check paths, permissions, available memory, and port availability.";
            if("json".equals(options.get("--format")))System.out.println(Json.write(Json.map("error",message,"exitCode",2)));
            else {System.err.println("ERROR  "+message);if(!(e instanceof InputError||e instanceof Store.Conflict))System.err.println("DETAIL "+e);}
            if(options.containsKey("--summary"))summary("### Cellar Guard could not complete the operation\n\nNothing was verified. See the job log for the input error.\n");
            return 2;
        }
    }
    private static String required(Map<String,String> options,String key) {String s=options.get(key);if(s==null||s.isBlank())throw new InputError("Required option: "+key);return s;}
    private static void summary(String text) {
        String path=System.getenv("GITHUB_STEP_SUMMARY");if(path==null||path.isBlank())return;
        try{Files.writeString(Path.of(path),text+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
        catch(Exception e){System.err.println("WARNING: Cannot write GitHub job summary.");}
    }
    private static void help(){System.out.println("""
        Cellar Guard — Java CSV compatibility checker
        java -jar dist/cellar.jar <command> [options]
          desktop                   Open the local desktop checker
          web [--port N]             Start the web app (default; no sign-in on localhost)
          audit-verify               Verify the tamper-evident audit chain (needs the master key)
          init                       Create sample cellar.json, never overwrite
          validate                   Validate the contract
          export-demo --out FILE     Generate a synthetic CSV; optional --demo-break
          check --csv FILE           Check declared header and row rules
            --format text|json|markdown --output FILE --advisory --summary --record
          diff --baseline FILE --csv FILE [--format json|markdown] [--output FILE] [--summary]
          history                    Read latest 100 locally saved check summaries
        --config FILE defaults to cellar.json; --data DIR defaults beside config/.cellar (web: CELLAR_DATA or ./data)
        Web settings come from CELLAR_* environment variables; see docs/WEB_SECURITY.md.
        (Config/data options are supported only on relevant commands.)
        Check exit codes: 0 allowed (includes advisory/warnings), 1 blocked, 2 invalid.
        Diff exit 0 means comparison completed, NOT contract compatibility.
        """);}
}
