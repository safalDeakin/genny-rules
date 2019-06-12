package life.genny.rules;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

import org.apache.http.client.ClientProtocolException;
import org.apache.logging.log4j.Logger;
import org.drools.core.impl.EnvironmentFactory;
import org.kie.api.KieBase;
import org.kie.api.KieBaseConfiguration;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.builder.ReleaseId;
import org.kie.api.event.process.DefaultProcessEventListener;
import org.kie.api.event.process.ProcessCompletedEvent;
import org.kie.api.event.process.ProcessNodeLeftEvent;
import org.kie.api.event.process.ProcessNodeTriggeredEvent;
import org.kie.api.event.process.ProcessStartedEvent;
import org.kie.api.event.process.ProcessVariableChangedEvent;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.Environment;
import org.kie.api.runtime.EnvironmentName;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import org.kie.api.runtime.conf.TimedRuleExecutionOption;
import org.kie.api.runtime.process.NodeInstance;
import org.kie.api.runtime.process.WorkflowProcessInstance;
import org.kie.internal.persistence.jpa.JPAKnowledgeService;
import org.kie.internal.runtime.StatefulKnowledgeSession;
import org.kie.api.runtime.Environment;
import org.kie.api.runtime.EnvironmentName;
import org.kie.api.runtime.KieContainer;

import com.google.common.io.Files;
import com.google.gson.reflect.TypeToken;

import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.Tuple3;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava.core.Vertx;
import io.vertx.rxjava.core.buffer.Buffer;
import life.genny.eventbus.EventBusInterface;
import life.genny.jbpm.customworkitemhandlers.AwesomeHandler;
import life.genny.jbpm.customworkitemhandlers.NotificationWorkItemHandler;
import life.genny.jbpm.customworkitemhandlers.ShowAllFormsHandler;
import life.genny.models.GennyToken;
import life.genny.qwanda.entity.User;
import life.genny.qwanda.message.QEventMessage;
import life.genny.qwandautils.GennySettings;
import life.genny.qwandautils.JsonUtils;
import life.genny.qwandautils.KeycloakUtils;
import life.genny.qwandautils.QwandaUtils;
import life.genny.utils.RulesUtils;
import life.genny.utils.VertxUtils;

public class RulesLoader {
	protected static final Logger log = org.apache.logging.log4j.LogManager
			.getLogger(MethodHandles.lookup().lookupClass().getCanonicalName());

	public static Map<String, KieBase> kieBaseCache = new ConcurrentHashMap<String, KieBase>();;
	static {
		setKieBaseCache(new HashMap<String, KieBase>());
	}

	public static KieServices ks = KieServices.Factory.get();

	public static Set<String> realms = new HashSet<String>();
	public static Set<String> userRoles = null;
	public static Map<String, User> usersSession = new HashMap<String, User>();

	static Environment env; // drools persistence
	
	static KieSessionConfiguration ksconf = null;


	public static void addRules(final String rulesDir, List<Tuple3<String, String, String>> newrules) {
		List<Tuple3<String, String, String>> rules = processFileRealms("genny", rulesDir, realms);
		rules.addAll(newrules);
		// realms = getRealms(rules);
		realms.stream().forEach(System.out::println);
		realms.remove("genny");
		setupKieRules("genny", rules); // run genny rules first
		for (String realm : realms) {
			setupKieRules(realm, rules);
		}
	}

	/**
	 * @param rulesDir
	 */
	public static void loadRules(final String rulesDir) {

		log.info("Setting up Persistence");
		EntityManagerFactory emf = null;

		try {
			emf = Persistence.createEntityManagerFactory("genny-persistence-jbpm-jpa");
			env = EnvironmentFactory.newEnvironment(); // KnowledgeBaseFactory.newEnvironment();
			env.set(EnvironmentName.ENTITY_MANAGER_FACTORY, emf);
		} catch (Exception e) {
			log.warn("No persistence enabled, are you running wildfly-rulesservice?");
		}

		log.info("Loading Rules and workflows!!!");

		List<String> activeRealms = new ArrayList<String>();
		JsonObject ar = VertxUtils.readCachedJson(GennySettings.GENNY_REALM, "REALMS");
		String ars = ar.getString("value");

		if (ars == null) {
			try {
				ars = QwandaUtils.apiGet(GennySettings.qwandaServiceUrl + "/utils/realms", "NOTREQUIRED");
			} catch (ClientProtocolException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		Type listType = new TypeToken<List<String>>() {
		}.getType();
		ars = ars.replaceAll("\\\"", "\"");
		activeRealms = JsonUtils.fromJson(ars, listType);
		realms = new HashSet<>(activeRealms);

		List<Tuple3<String, String, String>> rules = processFileRealms("genny", rulesDir, realms);
		log.info("LOADED ALL RULES");
//		realms = getRealms(rules);
		realms.stream().forEach(System.out::println);
		realms.remove("genny");
//		Integer rulesCount = setupKieRules("genny", rules); // rNo need to run genny rules
//		log.info("Rules Count for genny = "+rulesCount);

		for (String realm : activeRealms) {
			log.info("LOADING " + realm + " RULES");
			Integer rulesCount = setupKieRules(realm, rules);
			log.info("Rules Count for " + realm + " = " + rulesCount);
		}
		
		// set up kie conf
		ksconf = KieServices.Factory.get().newKieSessionConfiguration();
		ksconf.setOption(TimedRuleExecutionOption.YES);

	}

	/**
	 * @param vertx
	 * @return
	 */
	public static void triggerStartupRules(final String rulesDir, EventBusInterface eventBus) {
		log.info("Triggering Startup Rules for all realms");
		log.info("---- Realm:genny Startup Rules ----------");

		if (realms.isEmpty()) {
			initMsg("Event:INIT_STARTUP", "genny", new QEventMessage("EVT_MSG", "INIT_STARTUP"), eventBus);
		}
		// }
		else {
			for (String realm : realms) {

				// Trigger Startup Rules
				log.info("---- Realm:" + realm + " Startup Rules ----------");
				initMsg("Event:INIT_STARTUP", realm, new QEventMessage("EVT_MSG", "INIT_STARTUP"), eventBus);
			}
		}
		log.info("Startup Rules Triggered");
		try {

			// FileUtils.touch(new File("/tmp/ready"));
			Files.touch(new File("/tmp/ready"));
		} catch (IOException e) {
			log.info("Could not save readiness file");
		}

	}

	// private static List<Tuple2<String, String>> processFile(String inputFileStr)
	// {
	// File file = new File(inputFileStr);
	// String fileName = inputFileStr.replaceFirst(".*/(\\w+).*", "$1");
	// String fileNameExt = inputFileStr.replaceFirst(".*/\\w+\\.(.*)", "$1");
	// List<Tuple2<String, String>> rules = new ArrayList<Tuple2<String, String>>();

	// if (!file.isFile()) {
	// if (!fileName.startsWith("XX")) {
	// final List<String> filesList = Vertx.currentContext().owner().fileSystem()
	// .readDirBlocking(inputFileStr);

	// for (final String dirFileStr : filesList) {
	// List<Tuple2<String, String>> childRules = processFile(dirFileStr); // use
	// directory name as
	// // rulegroup
	// rules.addAll(childRules);
	// }
	// }
	// return rules;
	// } else {
	// Buffer buf =
	// Vertx.currentContext().owner().fileSystem().readFileBlocking(inputFileStr);
	// try {
	// if ((!fileName.startsWith("XX")) && (fileNameExt.equalsIgnoreCase("drl"))) {
	// // ignore files that start
	// // with XX
	// final String ruleText = buf.toString();

	// Tuple2<String, String> rule = (Tuple.of(fileName + "." + fileNameExt,
	// ruleText));
	// String filerule = inputFileStr.substring(inputFileStr.indexOf("/rules/"));
	// log.info("Loading in Rule:" + rule._1 + " of " + filerule);
	// rules.add(rule);
	// } else if ((!fileName.startsWith("XX")) &&
	// (fileNameExt.equalsIgnoreCase("bpmn"))) { // ignore files
	// // that start
	// // with XX
	// final String bpmnText = buf.toString();

	// Tuple2<String, String> bpmn = (Tuple.of(fileName + "." + fileNameExt,
	// bpmnText));
	// log.info("Loading in BPMN:" + bpmn._1 + " of " + inputFileStr);
	// rules.add(bpmn);
	// } else if ((!fileName.startsWith("XX")) &&
	// (fileNameExt.equalsIgnoreCase("xls"))) { // ignore files that
	// // start with XX
	// final String xlsText = buf.toString();

	// Tuple2<String, String> xls = (Tuple.of(fileName + "." + fileNameExt,
	// xlsText));
	// log.info("Loading in XLS:" + xls._1 + " of " + inputFileStr);
	// rules.add(xls);
	// }
	// return rules;
	// } catch (final DecodeException dE) {

	// }

	// }
	// return null;
	// }

	static public List<Tuple3<String, String, String>> processFileRealms(final String realm, String inputFileStrs,
			Set<String> activeRealms) {
		List<Tuple3<String, String, String>> rules = new ArrayList<Tuple3<String, String, String>>();

		String[] inputFileStrArray = inputFileStrs.split(";,"); // allow multiple rules dirs

		for (String inputFileStr : inputFileStrArray) {

			// log.info("InputFileStr=" + inputFileStr);
			File file = new File(inputFileStr);
			String fileName = inputFileStr.replaceFirst(".*/(\\w+).*", "$1");

			String fileNameExt = inputFileStr.replaceFirst(".*/\\w+\\.(.*)", "$1");
			if (!file.isFile()) { // DIRECTORY
				if (!fileName.startsWith("XX")) {
					String localRealm = realm;
					if (fileName.startsWith("prj_") || fileName.startsWith("PRJ_")) {
						String fileprj = fileName.substring("prj_".length());
						if ((activeRealms.stream().anyMatch(fileprj::equals))
								|| ("prj_genny".equalsIgnoreCase(fileName))) {
							localRealm = fileName.substring("prj_".length()).toLowerCase(); // extract realm name
						} else {
							continue;
						}
					}
					List<String> filesList = null;

					if (Vertx.currentContext() != null) {
						filesList = Vertx.currentContext().owner().fileSystem().readDirBlocking(inputFileStr);
					} else {
						final File folder = new File(inputFileStr);
						final File[] listOfFiles = folder.listFiles();
						filesList = new ArrayList<String>();
						if (listOfFiles != null) {
							for (File f : listOfFiles) {
								filesList.add(f.getAbsolutePath());
							}
						} else {
							log.info("No files to load from " + inputFileStrs);
						}
					}

					for (final String dirFileStr : filesList) {
						List<Tuple3<String, String, String>> childRules = processFileRealms(localRealm, dirFileStr,
								realms); // use
						// directory
						// name
						// as
						// rulegroup
						rules.addAll(childRules);
					}
				}

			} else {
				String nonVertxFileText = null;
				Buffer buf = null;
				if (Vertx.currentContext() != null) {
					buf = Vertx.currentContext().owner().fileSystem().readFileBlocking(inputFileStr);
				} else {
					try {
						nonVertxFileText = getFileAsText(inputFileStr);
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
				try {
					if ((!fileName.startsWith("XX")) && (fileNameExt.equalsIgnoreCase("drl"))) { // ignore files that
																									// start
																									// with XX
						String ruleText = null;
						if (Vertx.currentContext() != null) {
							ruleText = buf.toString();
						} else {
							ruleText = nonVertxFileText;
						}

						Tuple3<String, String, String> rule = (Tuple.of(realm, fileName + "." + fileNameExt, ruleText));
						String filerule = inputFileStr.substring(inputFileStr.indexOf("/rules/"));
						log.info("(" + realm + ") Loading in DRL Rule:" + rule._1 + " of " + inputFileStr);
						rules.add(rule);
					} else if ((!fileName.startsWith("XX")) && (fileNameExt.equalsIgnoreCase("bpmn"))) { // ignore files
																											// that
																											// start
																											// with XX
						String bpmnText = null;
						if (Vertx.currentContext() != null) {
							bpmnText = buf.toString();
						} else {
							bpmnText = nonVertxFileText;
						}

						Tuple3<String, String, String> bpmn = (Tuple.of(realm, fileName + "." + fileNameExt, bpmnText));
						log.info(realm + " Loading in BPMN:" + bpmn._1 + " of " + inputFileStr);
						rules.add(bpmn);
					} else if ((!fileName.startsWith("XX")) && (fileNameExt.equalsIgnoreCase("xls"))) { // ignore files
																										// that
																										// start with XX
						String xlsText = null;
						if (Vertx.currentContext() != null) {
							xlsText = buf.toString();
						} else {
							xlsText = nonVertxFileText;
						}

						Tuple3<String, String, String> xls = (Tuple.of(realm, fileName + "." + fileNameExt, xlsText));
						log.info(realm + " Loading in XLS:" + xls._1 + " of " + inputFileStr);
						rules.add(xls);
					}

				} catch (final DecodeException dE) {

				}

			}
		}
		return rules;
	}

	private static String getFileAsText(final String inputFilePath) throws IOException {
		File file = new File(inputFilePath);
		final BufferedReader in = new BufferedReader(new FileReader(file));
		String ret = "";
		String line = null;
		while ((line = in.readLine()) != null) {
			ret += line;
		}
		in.close();

		return ret;
	}

	public static Set<String> getRealms(final List<Tuple3<String, String, String>> rules) {
		Set<String> realms = new HashSet<String>();

		for (Tuple3<String, String, String> rule : rules) {
			String realm = rule._1;
			realms.add(realm);
		}
		return realms;
	}

	public static Integer setupKieRules(final String realm, final List<Tuple3<String, String, String>> rules) {
		Integer count = 0;
		try {
			// load up the knowledge base
			if (ks == null) {
				log.error("ks is NULL");
				ks = KieServices.Factory.get();
			}
			final KieFileSystem kfs = ks.newKieFileSystem();

			// Write each rule into it's realm cache
			for (final Tuple3<String, String, String> rule : rules) {
				if (writeRulesIntoKieFileSystem(realm, rules, kfs, rule)) {
					count++;
				}
			}

			final KieBuilder kieBuilder = ks.newKieBuilder(kfs).buildAll();
			if (kieBuilder.getResults().hasMessages(Message.Level.ERROR)) {
				log.info(kieBuilder.getResults().toString());
			}

			ReleaseId releaseId = kieBuilder.getKieModule().getReleaseId();

			final KieContainer kContainer = ks.newKieContainer(releaseId);
			final KieBaseConfiguration kbconf = ks.newKieBaseConfiguration();
			final KieBase kbase = kContainer.newKieBase(kbconf);

//			 KnowledgeBuilder kbuilder = KnowledgeBuilderFactory.newKnowledgeBuilder();
//			 kbuilder.add( ResourceFactory.newFileSystemResource( fileName ), ResourceType.DRL );
//			 assertFalse( kbuilder.hasErrors() );
//			 if (kbuilder.hasErrors() ) {
//			     System.out.println( kbuilder.getErrors() );
//			 }
//			 KnowledgeBase kbase = KnowledgeBaseFactory.newKnowledgeBase();
//			 kbase.addKnowledgePackages( kbuilder.getKnowledgePackages() );
//
//			 StatefulKnowledgeSession ksession = kbase.newKieSession();
//			 for( Object fact : facts ) {
//			     ksession.insert( fact );
//			 }
//			 ksession.fireAllRules();
//			 ksession.dispose();

			log.info("Put rules KieBase into Custom Cache");
			if (getKieBaseCache().containsKey(realm)) {
				getKieBaseCache().remove(realm);
				log.info(realm + " removed");
			}
			getKieBaseCache().put(realm, kbase);
			log.info(realm + " rules installed\n");

		} catch (final Throwable t) {
			t.printStackTrace();
		}
		return count;
	}

	/**
	 * @param realm
	 * @param rules
	 * @param kfs
	 * @param rule
	 */
	private static boolean writeRulesIntoKieFileSystem(final String realm,
			final List<Tuple3<String, String, String>> rules, final KieFileSystem kfs,
			final Tuple3<String, String, String> rule) {
		boolean ret = false;

		if (rule._1.equalsIgnoreCase("genny") || rule._1.equalsIgnoreCase(realm)) {
			// if a realm rule with same name exists as the same name as a genny rule then
			// ignore the genny rule
			if ((rule._1.equalsIgnoreCase("genny")) && (!"genny".equalsIgnoreCase(realm))) {
				String filename = rule._2;
				// check if realm rule exists, if so then continue
				// if (rules.stream().anyMatch(item -> ((!realm.equals("genny")) &&
				// realm.equals(item._1()) && filename.equals(item._2()))))
				// {
				// log.info(realm+" - Overriding genny rule "+rule._2);
				// return;
				// }
				for (Tuple3<String, String, String> ruleCheck : rules) { // look for rules that are not genny rules
					String realmCheck = ruleCheck._1;
					if (realmCheck.equals(realm)) {

						String filenameCheck = ruleCheck._2;
						if (filenameCheck.equalsIgnoreCase(filename)) {
							log.info("Ditching the genny rule because higher rule overrides:" + rule._1 + " : "
									+ rule._2);
							return false; // do not save this genny rule as there is a proper realm rule with same name
						}
					}

				}
			}
			if (rule._2.endsWith(".drl")) {
				final String inMemoryDrlFileName = "src/main/resources/" + rule._2;
				kfs.write(inMemoryDrlFileName, ks.getResources().newReaderResource(new StringReader(rule._3))
						.setResourceType(ResourceType.DRL));
			} else if (rule._2.endsWith(".bpmn")) {
				final String inMemoryDrlFileName = "src/main/resources/" + rule._2;
				kfs.write(inMemoryDrlFileName, ks.getResources().newReaderResource(new StringReader(rule._3))
						.setResourceType(ResourceType.BPMN2));
			} else if (rule._2.endsWith(".xls")) {
				final String inMemoryDrlFileName = "src/main/resources/" + rule._2;
				// Needs t handle byte[]
				// kfs.write(inMemoryDrlFileName, ks.getResources().newReaderResource(new
				// FileReader(rule._2))
				// .setResourceType(ResourceType.DTABLE));

			} else {
				final String inMemoryDrlFileName = "src/main/resources/" + rule._2;
				kfs.write(inMemoryDrlFileName, ks.getResources().newReaderResource(new StringReader(rule._3))
						.setResourceType(ResourceType.DRL));
			}
			return true;
		}
		return ret;
	}

	public static void executeStateless(final String realm, final EventBusInterface bus,
			final List<Tuple2<String, Object>> globals, final List<Object> facts,
			final Map<String, String> keyValueMap) {

		try {
			StatefulKnowledgeSession kieSession = null;
			if (getKieBaseCache().get(realm) == null) {
				log.error("The realm  kieBaseCache is null, not loaded " + realm);
				return;
			}

			KieSessionConfiguration ksconf = KieServices.Factory.get().newKieSessionConfiguration();
			ksconf.setOption(TimedRuleExecutionOption.YES);

			kieSession = (StatefulKnowledgeSession) getKieBaseCache().get(realm).newKieSession(ksconf, env);

			// Register handlers
			kieSession.getWorkItemManager().registerWorkItemHandler("Awesome", new AwesomeHandler());
			kieSession.getWorkItemManager().registerWorkItemHandler("Notification", new NotificationWorkItemHandler());
			kieSession.getWorkItemManager().registerWorkItemHandler("ShowAllForms", new ShowAllFormsHandler());

			int sessionId = kieSession.getId();
			log.info("Session id = " + sessionId);

			if (bus != null) { // assist testing
				kieSession.insert(bus);
			}

			// Load globals
			for (final Tuple2<String, Object> t : globals) {
				try {
					kieSession.setGlobal(t._1, t._2);
				} catch (java.lang.RuntimeException e) {
					log.info(e.getMessage());
				}
			}
			for (final Object fact : facts) {
				kieSession.insert(fact);
			}

			kieSession.insert(log);

			kieSession.insert(keyValueMap);

			// Set the focus on the Init agenda group to force proper startup
			kieSession.getAgenda().getAgendaGroup("Init").setFocus();

			int rulesFired = kieSession.fireAllRules();

			log.info("Fired " + rulesFired + " rules");
			log.info("finished rules");
			kieSession.dispose();
		} catch (final Throwable t) {
			t.printStackTrace();
		}
	}

	public static void executeStateful2(final String realm, final EventBusInterface bus,
			final List<Tuple2<String, Object>> globals, final List<Object> facts,
			final Map<String, String> keyValueMap) {

		try {
//			 KnowledgeBuilder kbuilder = KnowledgeBuilderFactory.newKnowledgeBuilder();
//			 kbuilder.add( ResourceFactory.newFileSystemResource( fileName ), ResourceType.DRL );
//			 assertFalse( kbuilder.hasErrors() );
//			 if (kbuilder.hasErrors() ) {
//			     System.out.println( kbuilder.getErrors() );
//			 }
//			 KnowledgeBase kbase = KnowledgeBaseFactory.newKnowledgeBase();
//			 kbase.addKnowledgePackages( kbuilder.getKnowledgePackages() );
//
//			 StatefulKnowledgeSession ksession = kbase.newKieSession();
//			 for( Object fact : facts ) {
//			     ksession.insert( fact );
//			 }
//			 ksession.fireAllRules();
//			 ksession.dispose();
			// KieSession kieSession = null;
			StatefulKnowledgeSession kieSession = null;
			if (getKieBaseCache().get(realm) == null) {
				log.error("The realm  kieBaseCache is null, not loaded " + realm);
				return;
			}

			KieSessionConfiguration ksconf = KieServices.Factory.get().newKieSessionConfiguration();
			ksconf.setOption(TimedRuleExecutionOption.YES);

			// create a new knowledge session that uses JPA to store the runtime state

				kieSession = JPAKnowledgeService.newStatefulKnowledgeSession(getKieBaseCache().get(realm), ksconf, env); // This
																															// is
																															// stateful

			// Register handlers
			kieSession.getWorkItemManager().registerWorkItemHandler("Awesome", new AwesomeHandler());
			kieSession.getWorkItemManager().registerWorkItemHandler("Notification", new NotificationWorkItemHandler());
			kieSession.getWorkItemManager().registerWorkItemHandler("ShowAllForms", new ShowAllFormsHandler());

			int sessionId = kieSession.getId();
			log.info("Session id = " + sessionId);

			// invoke methods on your method here
			for (final Object fact : facts) {
				if (fact instanceof QRules) {
					// QRules cannot be persisted 
					
				} else {
					kieSession.insert(fact);
				}
			}

			kieSession.insert(log);

			kieSession.insert(keyValueMap);

			kieSession.startProcess( "MyProcess" );
//
//			kieSession.dispose();

//			kieSession = getKieBaseCache().get(rulesGroup).newKieSession(ksconf, null);

			/*
			 * kSession.addEventListener(new DebugAgendaEventListener());
			 * kSession.addEventListener(new DebugRuleRuntimeEventListener());
			 */


//			kieSession.addEventListener(new DefaultProcessEventListener() {
//			    public void beforeProcessStarted(ProcessStartedEvent event) {
//			        WorkflowProcessInstance process = (WorkflowProcessInstance) event.getProcessInstance();
//			        log.info("jBPM event 'beforeProcessStarted'. Process ID: " + process.getId()
//			                + ", Process definition ID: " + process.getProcessId() + ", Process name: "
//			                + process.getProcessName() + ", Process state: " + process.getState() + ", Parent process ID: "
//			                + process.getParentProcessInstanceId());
//			    }
//
//			    public void afterProcessStarted(ProcessStartedEvent event) {
//			        WorkflowProcessInstance process = (WorkflowProcessInstance) event.getProcessInstance();
//			        log.info("jBPM event 'afterProcessStarted'. Process ID: " + process.getId()
//			                + ", Process definition ID: " + process.getProcessId() + ", Process name: "
//			                + process.getProcessName() + ", Process state: " + process.getState() + ", Parent process ID: "
//			                + process.getParentProcessInstanceId());
//			    }
//
//			    public void beforeProcessCompleted(ProcessCompletedEvent event) {
//			        WorkflowProcessInstance process = (WorkflowProcessInstance) event.getProcessInstance();
//			        log.info("jBPM event 'beforeProcessCompleted'. Process ID: " + process.getId()
//			                + ", Process definition ID: " + process.getProcessId() + ", Process name: "
//			                + process.getProcessName() + ", Process state: " + process.getState() + ", Parent process ID: "
//			                + process.getParentProcessInstanceId());
//			    }
//
//			    public void afterProcessCompleted(ProcessCompletedEvent event) {
//			        WorkflowProcessInstance process = (WorkflowProcessInstance) event.getProcessInstance();
//			        log.info("jBPM event 'afterProcessCompleted'. Process ID: " + process.getId()
//			                + ", Process definition ID: " + process.getProcessId() + ", Process name: "
//			                + process.getProcessName() + ", Process state: " + process.getState() + ", Parent process ID: "
//			                + process.getParentProcessInstanceId());
//			    }
//
//			    public void beforeNodeTriggered(ProcessNodeTriggeredEvent event) {
//			        WorkflowProcessInstance process = (WorkflowProcessInstance) event.getProcessInstance();
//			        NodeInstance node = event.getNodeInstance();
//			        log.info("jBPM event 'beforeNodeTriggered'. Process ID: " + process.getId()
//			                + ", Process definition ID: " + process.getProcessId() + ", Process name: "
//			                + process.getProcessName() + ", Process state: " + process.getState() + ", Parent process ID: "
//			                + process.getParentProcessInstanceId() + ", Node instance ID: " + node.getId() + ", Node ID: "
//			                + node.getNodeId() + ", Node name: " + node.getNodeName());
//
//			    }
//
//			    public void afterNodeTriggered(ProcessNodeTriggeredEvent event) {
//			        WorkflowProcessInstance process = (WorkflowProcessInstance) event.getProcessInstance();
//			        NodeInstance node = event.getNodeInstance();
//			        log.info("jBPM event 'afterNodeTriggered'. Process ID: " + process.getId()
//			                + ", Process definition ID: " + process.getProcessId() + ", Process name: "
//			                + process.getProcessName() + ", Process state: " + process.getState() + ", Parent process ID: "
//			                + process.getParentProcessInstanceId() + ", Node instance ID: " + node.getId() + ", Node ID: "
//			                + node.getNodeId() + ", Node name: " + node.getNodeName());
//
//			    }
//
//			    public void beforeNodeLeft(ProcessNodeLeftEvent event) {
//			        WorkflowProcessInstance process = (WorkflowProcessInstance) event.getProcessInstance();
//			        NodeInstance node = event.getNodeInstance();
//			        log.info("jBPM event 'beforeNodeLeft'. Process ID: " + process.getId() + ", Process definition ID: "
//			                + process.getProcessId() + ", Process name: " + process.getProcessName() + ", Process state: "
//			                + process.getState() + ", Parent process ID: " + process.getParentProcessInstanceId()
//			                + ", Node instance ID: " + node.getId() + ", Node ID: " + node.getNodeId() + ", Node name: "
//			                + node.getNodeName());
//
//			    }
//
//			    public void afterNodeLeft(ProcessNodeLeftEvent event) {
//			        WorkflowProcessInstance process = (WorkflowProcessInstance) event.getProcessInstance();
//			        NodeInstance node = event.getNodeInstance();
//			        log.info("jBPM event 'afterNodeLeft'. Process ID: " + process.getId() + ", Process definition ID: "
//			                + process.getProcessId() + ", Process name: " + process.getProcessName() + ", Process state: "
//			                + process.getState() + ", Parent process ID: " + process.getParentProcessInstanceId()
//			                + ", Node instance ID: " + node.getId() + ", Node ID: " + node.getNodeId() + ", Node name: "
//			                + node.getNodeName());
//
//			    }
//
//			    public void beforeVariableChanged(ProcessVariableChangedEvent event){
//			        WorkflowProcessInstance process = (WorkflowProcessInstance) event.getProcessInstance();
//			        log.info("jBPM event 'beforeVariableChanged'. Process ID: " + process.getId() + ", Process definition ID: "
//			                + process.getProcessId() + ", Process name: " + process.getProcessName() + ", Process state: "
//			                + process.getState() + ", Parent process ID: " + process.getParentProcessInstanceId()
//			                + ", Variable ID: " + event.getVariableId() + ", Variable instance ID: " + event.getVariableInstanceId() + ", Old value: "
//			                + (event.getOldValue() == null ? "null" : event.getOldValue().toString())+ ", New value: "+(event.getNewValue() == null ? "null" : event.getNewValue().toString()));
//			    }
//
//			    public void afterVariableChanged(ProcessVariableChangedEvent event){
//			        WorkflowProcessInstance process = (WorkflowProcessInstance) event.getProcessInstance();
//			        log.info("jBPM event 'afterVariableChanged'. Process ID: " + process.getId() + ", Process definition ID: "
//			                + process.getProcessId() + ", Process name: " + process.getProcessName() + ", Process state: "
//			                + process.getState() + ", Parent process ID: " + process.getParentProcessInstanceId()
//			                + ", Variable ID: " + event.getVariableId() + ", Variable instance ID: " + event.getVariableInstanceId() + ", Old value: "
//			                + (event.getOldValue() == null ? "null" : event.getOldValue().toString())+ ", New value: "+(event.getNewValue() == null ? "null" : event.getNewValue().toString()));
//			    }
//
//			});

			log.info("started workflow");
			kieSession.dispose();
		} catch (final Throwable t) {
			t.printStackTrace();
		}
	}

	public static Map<String, KieBase> getKieBaseCache() {
		return kieBaseCache;
	}

	public static void setKieBaseCache(Map<String, KieBase> kieBaseCache) {
		RulesLoader.kieBaseCache = kieBaseCache;

	}

	public static Map<String, Object> getDecodedTokenMap(final String token) {
		GennyToken gennyToken = new GennyToken(token);
		
		return gennyToken.getAdecodedTokenMap();
	}

	public static List<Tuple2<String, Object>> getStandardGlobals() {
		List<Tuple2<String, Object>> globals = new ArrayList<Tuple2<String, Object>>();
		String RESET = "\u001B[0m";
		String RED = "\u001B[31m";
		String GREEN = "\u001B[32m";
		String YELLOW = "\u001B[33m";
		String BLUE = "\u001B[34m";
		String PURPLE = "\u001B[35m";
		String CYAN = "\u001B[36m";
		String WHITE = "\u001B[37m";
		String BOLD = "\u001b[1m";

		globals.add(Tuple.of("LOG_RESET", RESET));
		globals.add(Tuple.of("LOG_RED", RED));
		globals.add(Tuple.of("LOG_GREEN", GREEN));
		globals.add(Tuple.of("LOG_YELLOW", YELLOW));
		globals.add(Tuple.of("LOG_BLUE", BLUE));
		globals.add(Tuple.of("LOG_PURPLE", PURPLE));
		globals.add(Tuple.of("LOG_CYAN", CYAN));
		globals.add(Tuple.of("LOG_WHITE", WHITE));
		globals.add(Tuple.of("LOG_BOLD", BOLD));
		globals.add(Tuple.of("REACT_APP_QWANDA_API_URL", GennySettings.qwandaServiceUrl));
		return globals;
	}

	public static void initMsgs(final String msgType, final Object msg, final EventBusInterface eventBus) {
		if (realms.isEmpty()) {
			initMsg("Event:INIT_STARTUP", "genny", new QEventMessage("EVT_MSG", "INIT_STARTUP"), eventBus);
		}
		// }
		else {
			for (String realm : realms) {

				// Trigger Startup Rules
				log.info("---- Realm:" + realm + " Startup Rules ----------");
				initMsg("Event:INIT_STARTUP", realm, new QEventMessage("EVT_MSG", "INIT_STARTUP"), eventBus);
			}
		}
		log.info("Startup Rules Triggered");
		try {

			// FileUtils.touch(new File("/tmp/ready"));
			Files.touch(new File("/tmp/ready"));
		} catch (IOException e) {
			log.info("Could not save readiness file");
		}
	}

	public static void initMsg(final String msgType, String realm, final Object msg, final EventBusInterface eventBus) {

		Set<String> auserRoles = new HashSet<String>();
		auserRoles.add("admin");
		auserRoles.add("user");

		// Service Token
		String token = VertxUtils.getObject(realm, "CACHE", "SERVICE_TOKEN", String.class);

		if ("DUMMY".equalsIgnoreCase(token)) {
			log.error("NO SERVICE TOKEN FOR " + realm + " IN CACHE");
			return;
		}

		QRules qRules = new QRules(eventBus, token);
		qRules.set("realm", realm);
		qRules.setServiceToken(token);
		
		GennyToken serviceToken = new GennyToken("PER_SERVICE",token);

		List<Tuple2<String, Object>> globals = RulesLoader.getStandardGlobals();

		List<Object> facts = new ArrayList<Object>();
		facts.add(qRules);
		facts.add(msg);
		facts.add(qRules.getDecodedTokenMap());
		facts.add(serviceToken);
		facts.add(auserRoles);
		User currentUser = new User("service", "Service", realm, "admin");
		facts.add(currentUser);

		Map<String, String> keyvalue = new HashMap<String, String>();
		// calculate service token for this ...
		log.info("Realm:" + realm + " -> generated service token=" + token);
		keyvalue.put("token", token);

		try {
			executeStateless(realm, eventBus, globals, facts, keyvalue);
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	public static void processMsg(final String msgType, String ruleGroup, final Object msg,
			final EventBusInterface eventBus, final String token) {

		Map<String, Object> adecodedTokenMap = RulesLoader.getDecodedTokenMap(token);
		// check for token expiry

		Set<String> auserRoles = KeycloakUtils.getRoleSet(adecodedTokenMap.get("realm_access").toString());
		User userInSession = usersSession.get(adecodedTokenMap.get("preferred_username").toString());

		String preferredUName = adecodedTokenMap.get("preferred_username").toString();
		String fullName = adecodedTokenMap.get("name").toString();
		String accessRoles = adecodedTokenMap.get("realm_access").toString();

		QRules qRules = new QRules(eventBus, token);
		qRules.set("realm", qRules.realm());

		// Service Token
		String serviceToken = VertxUtils.getObject(qRules.realm(), "CACHE", "SERVICE_TOKEN", String.class);
		qRules.setServiceToken(serviceToken);

		List<Tuple2<String, Object>> globals = new ArrayList<Tuple2<String, Object>>();
		RulesLoader.getStandardGlobals();

		List<Object> facts = new ArrayList<Object>();
		facts.add(qRules);
		facts.add(msg);
		facts.add(adecodedTokenMap);
		facts.add(auserRoles);
		if (userInSession != null)
			facts.add(usersSession.get(preferredUName));
		else {
			User currentUser = new User(preferredUName, fullName, qRules.realm(), accessRoles);
			usersSession.put(adecodedTokenMap.get("preferred_username").toString(), currentUser);
			facts.add(currentUser);
		}

		Map<String, String> keyvalue = new HashMap<String, String>();
		keyvalue.put("token", token);

		if (!"GPS".equals(msgType)) {
			log.info("FIRE RULES (" + qRules.realm() + ") " + msgType);
		}

		try {
				RulesLoader.executeStateless(qRules.realm(), eventBus, globals, facts, keyvalue);

		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	public static Map<File, ResourceType> getKieResources() {
		return new HashMap<File, ResourceType>(); // TODO
	}

	public static KieSession setupStatefulKieSession(final String realm)
	{
		StatefulKnowledgeSession kieSession = JPAKnowledgeService.newStatefulKnowledgeSession(getKieBaseCache().get(realm), ksconf, env); // This
		addHandlers(kieSession);
		return kieSession;
	}
	
	public static void addHandlers(KieSession kieSession)
	{
		// Register handlers
		kieSession.getWorkItemManager().registerWorkItemHandler("Awesome", new AwesomeHandler());
		kieSession.getWorkItemManager().registerWorkItemHandler("Notification", new NotificationWorkItemHandler());
		kieSession.getWorkItemManager().registerWorkItemHandler("ShowAllForms", new ShowAllFormsHandler());

	}
	
	public static void processStatefulMessage(QEventMessage message,final GennyToken gennyToken) {

		final String realm = gennyToken.getToken();
		// Service Token
		String serviceTokenStr = VertxUtils.getObject(realm, "CACHE", "SERVICE_TOKEN", String.class);
		GennyToken serviceToken = new GennyToken("PER_SERVICE",serviceTokenStr);
		List<Tuple2<String, Object>> globals = new ArrayList<Tuple2<String, Object>>();
		RulesLoader.getStandardGlobals();



		try {
			if (getKieBaseCache().get(realm) == null) {
				log.error("The realm  kieBaseCache is null, not loaded " + realm);
				return;
			}

			// create a new knowledge session that uses JPA to store the runtime state
			// is
			KieSession kieSession = setupStatefulKieSession(realm);
			int sessionId = kieSession.getId();
			log.info("Session id = " + sessionId);

			kieSession.insert(log);

			kieSession.insert(message);
			kieSession.insert(gennyToken);
			kieSession.insert(serviceToken);

			int rulesFired = kieSession.fireAllRules();

			log.info("Fired " + rulesFired + " rules");
			log.info("finished rules");
			kieSession.dispose();


		} catch (Exception e) {
			e.printStackTrace();
		}

	}
}
