/**
 * Copyright (C) 2012 Spotify AB
 */

package com.spotify.helios;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import com.fasterxml.jackson.core.type.TypeReference;
import com.kpelykh.docker.client.DockerClient;
import com.kpelykh.docker.client.DockerException;
import com.kpelykh.docker.client.model.Container;
import com.kpelykh.docker.client.utils.LogReader;
import com.spotify.helios.agent.AgentMain;
import com.spotify.helios.cli.CliMain;
import com.spotify.helios.common.Client;
import com.spotify.helios.common.Json;
import com.spotify.helios.servicescommon.ServiceMain;
import com.spotify.helios.common.descriptors.AgentStatus;
import com.spotify.helios.common.descriptors.Deployment;
import com.spotify.helios.common.descriptors.Job;
import com.spotify.helios.common.descriptors.JobId;
import com.spotify.helios.common.descriptors.PortMapping;
import com.spotify.helios.common.descriptors.ServiceEndpoint;
import com.spotify.helios.common.descriptors.ServicePorts;
import com.spotify.helios.common.descriptors.TaskStatus;
import com.spotify.helios.common.descriptors.TaskStatus.State;
import com.spotify.helios.common.descriptors.ThrottleState;
import com.spotify.helios.common.protocol.CreateJobResponse;
import com.spotify.helios.common.protocol.JobDeleteResponse;
import com.spotify.helios.common.protocol.JobDeployResponse;
import com.spotify.helios.common.protocol.JobStatus;
import com.spotify.helios.common.protocol.JobUndeployResponse;
import com.spotify.helios.common.protocol.SetGoalResponse;
import com.spotify.helios.master.MasterMain;
import com.spotify.hermes.Hermes;
import com.spotify.logging.LoggingConfigurator;
import com.spotify.nameless.Service;
import com.spotify.nameless.api.EndpointFilter;
import com.spotify.nameless.api.NamelessClient;
import com.spotify.nameless.proto.Messages;
import com.sun.jersey.api.client.ClientResponse;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Optional.fromNullable;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Lists.newArrayList;
import static com.spotify.helios.common.descriptors.AgentStatus.Status.DOWN;
import static com.spotify.helios.common.descriptors.AgentStatus.Status.UP;
import static com.spotify.helios.common.descriptors.Goal.START;
import static com.spotify.helios.common.descriptors.Goal.STOP;
import static com.spotify.helios.common.descriptors.TaskStatus.State.EXITED;
import static com.spotify.helios.common.descriptors.TaskStatus.State.RUNNING;
import static com.spotify.helios.common.descriptors.TaskStatus.State.STOPPED;
import static com.spotify.helios.common.descriptors.ThrottleState.FLAPPING;
import static com.spotify.helios.common.descriptors.ThrottleState.IMAGE_MISSING;
import static java.lang.String.format;
import static java.lang.System.getenv;
import static java.lang.System.nanoTime;
import static java.lang.System.out;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(MockitoJUnitRunner.class)
public class SystemTest extends ZooKeeperTestBase {

  private static final String PREFIX = Long.toHexString(new SecureRandom().nextLong());

  private static final int WAIT_TIMEOUT_SECONDS = 40;
  private static final int LONG_WAIT_MINUTES = 2;

  private static final int INTERNAL_PORT = 4444;
  private static final int EXTERNAL_PORT = new SecureRandom().nextInt(10000) + 30000;

  private static final Map<String, String> EMPTY_ENV = emptyMap();
  private static final Map<String, PortMapping> EMPTY_PORTS = emptyMap();
  private static final Map<ServiceEndpoint, ServicePorts> EMPTY_REGISTRATION = emptyMap();

  private static final JobId BOGUS_JOB = new JobId("bogus", "job", "badfood");
  private static final String BOGUS_AGENT = "BOGUS_AGENT";

  private static final String TEST_USER = "TestUser";
  private static final String TEST_AGENT = "test-agent";
  private static final List<String> DO_NOTHING_COMMAND = asList("sh", "-c",
                                                                "while :; do sleep 1; done");

  public static final TypeReference<Map<JobId, JobStatus>> STATUSES_TYPE =
      new TypeReference<Map<JobId, JobStatus>>() {};

  private final int masterPort = PortAllocator.allocatePort("helios master");
  private final String masterEndpoint = "tcp://localhost:" + masterPort;
  private final String masterName = "test-master";

  private static final String DOCKER_ENDPOINT =
      fromNullable(getenv("DOCKER_ENDPOINT")).or("http://localhost:4160");

  private List<ServiceMain> mains = newArrayList();
  private final ExecutorService executorService = Executors.newCachedThreadPool();
  private Service nameless;

  public static final TypeReference<Map<String, Object>> OBJECT_TYPE =
      new TypeReference<Map<String, Object>>() {};

  private Path agentStateDir;

  @Rule
  public ExpectedException exception = ExpectedException.none();

  public static final String JOB_NAME = PREFIX + "foo";
  public static final String JOB_VERSION = "17";

  private final List<Client> clients = Lists.newArrayList();
  private final List<com.spotify.hermes.service.Client> hermesClients = Lists.newArrayList();

  @Override
  @Before
  public void setUp() throws Exception {
    listThreads();
    nameless = new Service();
    nameless.start();
    LoggingConfigurator.configure(new File(getClass().getResource("/logback-test.xml").getFile()));
    super.setUp();
    ensure("/config");
    ensure("/status");
    agentStateDir = Files.createTempDirectory("helios-agent");
  }

  @Override
  @After
  public void teardown() throws Exception {
    for (final Client client : clients) {
      client.close();
    }
    clients.clear();

    for (com.spotify.hermes.service.Client client : hermesClients) {
      client.close();
    }
    hermesClients.clear();

    for (final ServiceMain main : mains) {
      try {
        main.stopAsync();
        main.awaitTerminated();
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    mains = null;

    try {
      executorService.shutdownNow();
      executorService.awaitTermination(30, SECONDS);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }

    try {
      nameless.stop();
    } catch (Exception e) {
      e.printStackTrace();
    }

    // Clean up docker
    try {
      final DockerClient dockerClient = new DockerClient(DOCKER_ENDPOINT);
      final List<Container> containers = dockerClient.listContainers(false);
      for (final Container container : containers) {
        for (final String name : container.names) {
          if (name.contains(PREFIX)) {
            try {
              dockerClient.kill(container.id);
            } catch (DockerException e) {
              e.printStackTrace();
            }
            break;
          }
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }

    try {
      FileUtils.deleteDirectory(agentStateDir.toFile());
    } catch (IOException e) {
      e.printStackTrace();
    }

    super.teardown();

    listThreads();
  }

  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  private void listThreads() {
    final Set<Thread> threads = Thread.getAllStackTraces().keySet();
    final Map<String, Thread> sorted = Maps.newTreeMap();
    for (final Thread thread : threads) {
      if (thread.isAlive() && !thread.getThreadGroup().getName().equals("system")) {
        sorted.put(thread.getName(), thread);
      }
    }
    out.println("= THREADS " + Strings.repeat("=", 70));
    for (final Thread t : sorted.values()) {
      final ThreadGroup tg = t.getThreadGroup();
      out.printf("%4d: \"%s\" (%s%s)%n", t.getId(), t.getName(),
                 (tg == null ? "" : tg.getName() + " "),
                 (t.isDaemon() ? "daemon" : ""));
    }
    out.println(Strings.repeat("=", 80));
  }


  private ByteArrayOutputStream main(final String... args) throws Exception {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();
    final CliMain main = new CliMain(new PrintStream(out), new PrintStream(err), args);
    main.run();
    return out;
  }

  private MasterMain startMaster(final String... args) throws Exception {
    final MasterMain main = new MasterMain(args);
    main.startAsync();
    main.awaitRunning();
    mains.add(main);
    return main;
  }

  private AgentMain startAgent(final String... args) throws Exception {
    final AgentMain main = new AgentMain(args);
    main.startAsync();
    main.awaitRunning();
    mains.add(main);
    return main;
  }

  private ByteArrayOutputStream main(final Collection<String> args) throws Exception {
    return main(args.toArray(new String[args.size()]));
  }

  private String control(final String command, final String sub, final Object... args)
      throws Exception {
    return control(command, sub, flatten(args));
  }

  private String control(final String command, final String sub, final String... args)
      throws Exception {
    return control(command, sub, asList(args));
  }

  private String control(final String command, final String sub, final List<String> args)
      throws Exception {
    final List<String> commands = asList(command, sub, "-z", masterEndpoint, "--no-log-setup");
    final List<String> allArgs = newArrayList(concat(commands, args));
    return main(allArgs).toString();
  }

  private void awaitAgentRegistered(final String name, final long timeout, final TimeUnit timeUnit)
      throws Exception {
    await(timeout, timeUnit, new Callable<Object>() {
      @Override
      public Object call() throws Exception {
        final String output = control("host", "list", "-q");
        return output.contains(name) ? true : null;
      }
    });
  }

  private AgentStatus awaitAgentStatus(final String name,
                                       final AgentStatus.Status status,
                                       final int timeout,
                                       final TimeUnit timeUnit) throws Exception {
    return await(timeout, timeUnit, new Callable<AgentStatus>() {
      @Override
      public AgentStatus call() throws Exception {
        final String output = control("host", "status", name, "--json");
        final Map<String, AgentStatus> statuses = Json.read(
            output, new TypeReference<Map<String, AgentStatus>>() {});
        final AgentStatus agentStatus = statuses.get(name);
        if (agentStatus == null) {
          return null;
        }
        return (agentStatus.getStatus() == status) ? agentStatus : null;
      }
    });
  }

  private void assertContains(String needle, String haystack) {
    assertThat(haystack, containsString(needle));
  }

  private void assertNotContains(String needle, String haystack) {
    assertThat(haystack, not(containsString(needle)));
  }

  private void deployJob(final JobId jobId, final String agent)
      throws Exception {
    final String deployOutput = control("job", "deploy", jobId.toString(), agent);
    assertContains(agent + ": done", deployOutput);

    final String listOutput = control("host", "jobs", "-q", agent);
    assertContains(jobId.toString(), listOutput);
  }

  private List<String> flatten(final Object... values) {
    final Iterable<Object> valuesList = asList(values);
    return flatten(valuesList);
  }

  private List<String> flatten(final Iterable<?> values) {
    final List<String> list = new ArrayList<>();
    for (Object value : values) {
      if (value instanceof Iterable) {
        list.addAll(flatten((Iterable<?>) value));
      } else if (value.getClass() == String[].class) {
        list.addAll(asList((String[]) value));
      } else if (value instanceof String) {
        list.add((String) value);
      } else {
        throw new IllegalArgumentException();
      }
    }
    return list;
  }

  private void undeployJob(final JobId jobId, final String host) throws Exception {
    final String bogusUndeployAgentWrong =
        control("job", "undeploy", jobId.toString(), BOGUS_AGENT);
    assertContains("AGENT_NOT_FOUND", bogusUndeployAgentWrong);

    final String bogusUndeployJobWrong = control("job", "undeploy", BOGUS_JOB.toString(), host);
    assertContains("Unknown job", bogusUndeployJobWrong);

    final String undeployOutput = control("job", "undeploy", jobId.toString(), host);
    assertContains(host + ": done", undeployOutput);

    final String listOutput = control("host", "jobs", "-q", host);
    assertNotContains(jobId.toString(), listOutput);
  }

  @Test
  public void verifyMultipleAgentsWithTheSameStateDirFail() throws Exception {
    startDefaultAgent(TEST_AGENT);
    exception.expect(IllegalStateException.class);
    startDefaultAgent(TEST_AGENT);
  }

  @Test
  public void testMasterNamelessRegistration() throws Exception {
    startMaster("-vvvv",
                "--no-log-setup",
                "--munin-port", "0",
                "--site", "localhost",
                "--http", "0.0.0.0:" + EXTERNAL_PORT,
                "--hm", masterEndpoint,
                "--zk", zookeeperEndpoint);

    // sleep for half a second to give master time to register with nameless
    Thread.sleep(500);
    NamelessClient client = new NamelessClient(hermesClient("tcp://localhost:4999"));
    List<Messages.RegistryEntry> entries = client.queryEndpoints(EndpointFilter.everything()).get();

    assertEquals("wrong number of nameless entries", 2, entries.size());

    boolean httpFound = false;
    boolean hermesFound = false;

    for (Messages.RegistryEntry entry : entries) {
      final Messages.Endpoint endpoint = entry.getEndpoint();
      assertEquals("wrong service", "helios", endpoint.getService());
      final String protocol = endpoint.getProtocol();

      switch (protocol) {
        case "hm":
          hermesFound = true;
          assertEquals("wrong port", endpoint.getPort(), masterPort);
          break;
        case "http":
          httpFound = true;
          assertEquals("wrong port", endpoint.getPort(), EXTERNAL_PORT);
          break;
        default:
          fail("unknown protocol " + protocol);
      }
    }

    assertTrue("missing hermes nameless entry", hermesFound);
    assertTrue("missing http nameless entry", httpFound);
  }

  private com.spotify.hermes.service.Client hermesClient(final String... endpoints) {
    final com.spotify.hermes.service.Client client = Hermes.newClient(endpoints);
    hermesClients.add(client);
    return client;
  }

  @Test
  public void testContainerNamelessRegistration() throws Exception {
    startDefaultMaster();

    final Client client = defaultClient();

    startDefaultAgent(TEST_AGENT, "--site=localhost");
    awaitAgentStatus(client, TEST_AGENT, UP, WAIT_TIMEOUT_SECONDS, SECONDS);

    ImmutableMap<String, PortMapping> portMapping = ImmutableMap.of(
        "PORT_NAME", PortMapping.of(INTERNAL_PORT, EXTERNAL_PORT));

    final String serviceName = "SERVICE";
    final String serviceProto = "PROTO";

    ImmutableMap<ServiceEndpoint, ServicePorts> registration = ImmutableMap.of(
        ServiceEndpoint.of(serviceName, serviceProto), ServicePorts.of("PORT_NAME"));

    final JobId jobId = createJob(JOB_NAME, JOB_VERSION, "busybox", DO_NOTHING_COMMAND,
                                  EMPTY_ENV, portMapping, registration);

    deployJob(jobId, TEST_AGENT);
    awaitJobState(client, TEST_AGENT, jobId, RUNNING, WAIT_TIMEOUT_SECONDS, SECONDS);
    // Give it some time for the registration to register.
    Thread.sleep(1000);
    final NamelessClient nameless = new NamelessClient(hermesClient("tcp://localhost:4999"));
    final EndpointFilter filter = EndpointFilter.newBuilder()
        .port(EXTERNAL_PORT)
        .protocol(serviceProto)
        .service(serviceName)
        .build();

    final List<Messages.RegistryEntry> entries = nameless.queryEndpoints(filter).get();
    assertTrue(entries.size() == 1);
    final Messages.RegistryEntry entry = entries.get(0);
    final Messages.Endpoint endpoint = entry.getEndpoint();
    assertEquals("wrong service", serviceName, endpoint.getService());
    assertEquals("wrong protocol", serviceProto, endpoint.getProtocol());
    assertEquals("wrong port", endpoint.getPort(), EXTERNAL_PORT);
  }

  private Client defaultClient() {
    return client(TEST_USER, masterEndpoint);
  }

  private Client client(final String user, final String endpoint) {
    final Client client = Client.newBuilder()
        .setUser(user)
        .setEndpoints(endpoint)
        .build();
    clients.add(client);
    return client;
  }

  // TODO (dano): restore task status history and make sure that it's bounded as well
//  @Test
//  public void testJobHistory() throws Exception {
//    startDefaultMaster();
//
//    final Client client = Client.newBuilder()
//        .setUser(TEST_USER)
//        .setEndpoints(masterEndpoint)
//        .build();
//
//    startDefaultAgent(TEST_AGENT);
//    JobId jobId = createJob(JOB_NAME, JOB_VERSION, "busybox", ImmutableList.of("/bin/true"));
//    deployJob(jobId, TEST_AGENT);
//    awaitJobState(client, TEST_AGENT, jobId, EXITED, WAIT_TIMEOUT_SECONDS, SECONDS);
//    undeployJob(jobId, TEST_AGENT);
//    TaskStatusEvents events = client.jobHistory(jobId).get();
//    List<TaskStatusEvent> eventsList = events.getEvents();
//    assertFalse(eventsList.isEmpty());
//
//    final TaskStatusEvent event1 = eventsList.get(0);
//    assertEquals(State.CREATING, event1.getStatus().getState());
//    assertNull(event1.getStatus().getContainerId());
//
//    final TaskStatusEvent event2 = eventsList.get(1);
//    assertEquals(State.STARTING, event2.getStatus().getState());
//    assertNotNull(event2.getStatus().getContainerId());
//
//    final TaskStatusEvent event3 = eventsList.get(2);
//    assertEquals(State.RUNNING, event3.getStatus().getState());
//
//    final TaskStatusEvent event4 = eventsList.get(3);
//    assertEquals(State.EXITED, event4.getStatus().getState());
//  }

  @Test
  public void testFlapping() throws Exception {
    startDefaultMaster();
    startDefaultAgent(TEST_AGENT);

    final Client client = defaultClient();

    awaitAgentStatus(client, TEST_AGENT, UP, WAIT_TIMEOUT_SECONDS, SECONDS);

    JobId jobId = createJob(JOB_NAME, JOB_VERSION, "busybox", ImmutableList.of("/bin/true"));
    deployJob(jobId, TEST_AGENT);
    awaitJobThrottle(client, TEST_AGENT, jobId, FLAPPING, WAIT_TIMEOUT_SECONDS, SECONDS);
  }

  @Test
  public void testMultiplePorts() throws Exception {
    startDefaultMaster();
    startDefaultAgent(TEST_AGENT);

    final Client client = defaultClient();

    awaitAgentStatus(client, TEST_AGENT, UP, WAIT_TIMEOUT_SECONDS, SECONDS);

    final Map<String, PortMapping> ports = ImmutableMap.of("foo", PortMapping.of(4711),
                                                           "bar", PortMapping.of(6000));

    final JobId jobId = createJob(JOB_NAME, JOB_VERSION, "busybox", DO_NOTHING_COMMAND, EMPTY_ENV,
                                  ports);
    assertNotNull(jobId);
    deployJob(jobId, TEST_AGENT);
    awaitJobState(client, TEST_AGENT, jobId, State.RUNNING, WAIT_TIMEOUT_SECONDS, SECONDS);
  }

  @Test
  public void testImageMissing() throws Exception {
    startDefaultMaster();
    startDefaultAgent(TEST_AGENT);

    final Client client = defaultClient();

    awaitAgentStatus(client, TEST_AGENT, UP, WAIT_TIMEOUT_SECONDS, SECONDS);

    JobId jobId = createJob(JOB_NAME, JOB_VERSION, "this_sould_not_exist",
                            ImmutableList.of("/bin/true"));

    deployJob(jobId, TEST_AGENT);
    awaitJobThrottle(client, TEST_AGENT, jobId, IMAGE_MISSING, WAIT_TIMEOUT_SECONDS,
                     SECONDS);

    final AgentStatus agentStatus = client.agentStatus(TEST_AGENT).get();
    final TaskStatus taskStatus = agentStatus.getStatuses().get(jobId);
    assertEquals(TaskStatus.State.FAILED, taskStatus.getState());
  }

  @Test
  public void testImageNameBogus() throws Exception {
    startDefaultMaster();
    exception.expect(IllegalArgumentException.class);
    createJob(JOB_NAME, JOB_VERSION, "DOES_NOT_LIKE_AT_ALL-CAPITALS",
              ImmutableList.of("/bin/true"));
  }

  @Test
  public void testPortCollision() throws Exception {
    final int externalPort = 4711;

    startDefaultMaster();
    startDefaultAgent(TEST_AGENT);

    final Client client = defaultClient();

    awaitAgentStatus(client, TEST_AGENT, UP, WAIT_TIMEOUT_SECONDS, SECONDS);

    final Job job1 = Job.newBuilder()
        .setName(PREFIX + "foo")
        .setVersion("1")
        .setImage("busybox")
        .setCommand(DO_NOTHING_COMMAND)
        .setPorts(ImmutableMap.of("foo", PortMapping.of(10001, externalPort)))
        .build();

    final Job job2 = Job.newBuilder()
        .setName(PREFIX + "bar")
        .setVersion("1")
        .setImage("busybox")
        .setCommand(DO_NOTHING_COMMAND)
        .setPorts(ImmutableMap.of("foo", PortMapping.of(10002, externalPort)))
        .build();

    final CreateJobResponse created1 = client.createJob(job1).get();
    assertEquals(CreateJobResponse.Status.OK, created1.getStatus());

    final CreateJobResponse created2 = client.createJob(job2).get();
    assertEquals(CreateJobResponse.Status.OK, created2.getStatus());

    final Deployment deployment1 = Deployment.of(job1.getId(), STOP);
    final JobDeployResponse deployed1 = client.deploy(deployment1, TEST_AGENT).get();
    assertEquals(JobDeployResponse.Status.OK, deployed1.getStatus());

    final Deployment deployment2 = Deployment.of(job2.getId(), STOP);
    final JobDeployResponse deployed2 = client.deploy(deployment2, TEST_AGENT).get();
    assertEquals(JobDeployResponse.Status.PORT_CONFLICT, deployed2.getStatus());
  }

  @Test
  public void testCreateJobWithIdMismatchFails() throws Exception {
    startDefaultMaster();

    final Client client = defaultClient();

    final CreateJobResponse createIdMismatch = client.createJob(
        new Job(JobId.fromString("bad:job:deadbeef"), "busyBox", DO_NOTHING_COMMAND, EMPTY_ENV,
                EMPTY_PORTS, EMPTY_REGISTRATION)).get();

    // TODO (dano): Maybe this should be ID_MISMATCH but then JobValidator must become able to communicate that
    assertEquals(CreateJobResponse.Status.INVALID_JOB_DEFINITION, createIdMismatch.getStatus());
  }

  @Test
  public void testTimeoutMessage() throws Exception {
    final String[] commands = {"job", "list", "--no-log-setup", "-s", "bogussite"};

    final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    new CliMain(new PrintStream(stdout), new PrintStream(stderr), commands).run();
    String string = stderr.toString();
    assertContains("we tried to connect to", string);
    assertContains("bogussite", string);
  }

  @Test
  public void testService() throws Exception {
    final Map<String, PortMapping> ports = ImmutableMap.of("foos", PortMapping.of(17, 4711));

    startDefaultMaster();

    final Client client = defaultClient();

    AgentStatus v = client.agentStatus(TEST_AGENT).get();
    assertNull(v); // for NOT_FOUND

    final AgentMain agent = startDefaultAgent(TEST_AGENT);

    // Create a job
    final Job job = Job.newBuilder()
        .setName(JOB_NAME)
        .setVersion(JOB_VERSION)
        .setImage("busybox")
        .setCommand(DO_NOTHING_COMMAND)
        .setPorts(ports)
        .build();
    final JobId jobId = job.getId();
    final CreateJobResponse created = client.createJob(job).get();
    assertEquals(CreateJobResponse.Status.OK, created.getStatus());

    final CreateJobResponse duplicateJob = client.createJob(job).get();
    assertEquals(CreateJobResponse.Status.JOB_ALREADY_EXISTS, duplicateJob.getStatus());

    // Try querying for the job
    final Map<JobId, Job> noMatchJobs = client.jobs(JOB_NAME + "not_matching").get();
    assertTrue(noMatchJobs.isEmpty());

    final Map<JobId, Job> matchJobs1 = client.jobs(JOB_NAME).get();
    assertEquals(ImmutableMap.of(jobId, job), matchJobs1);

    final Map<JobId, Job> matchJobs2 = client.jobs(JOB_NAME + ":" + JOB_VERSION).get();
    assertEquals(ImmutableMap.of(jobId, job), matchJobs2);

    final Map<JobId, Job> matchJobs3 = client.jobs(job.getId().toString()).get();
    assertEquals(ImmutableMap.of(jobId, job), matchJobs3);

    // Wait for agent to come up
    awaitAgentRegistered(client, TEST_AGENT, WAIT_TIMEOUT_SECONDS, SECONDS);
    awaitAgentStatus(client, TEST_AGENT, UP, WAIT_TIMEOUT_SECONDS, SECONDS);

    // Deploy the job on the agent
    final Deployment deployment = Deployment.of(jobId, START);
    final JobDeployResponse deployed = client.deploy(deployment, TEST_AGENT).get();
    assertEquals(JobDeployResponse.Status.OK, deployed.getStatus());

    final JobDeployResponse deployed2 = client.deploy(deployment, TEST_AGENT).get();
    assertEquals(JobDeployResponse.Status.JOB_ALREADY_DEPLOYED, deployed2.getStatus());

    final JobDeployResponse deployed3 = client.deploy(Deployment.of(BOGUS_JOB, START),
                                                      TEST_AGENT).get();
    assertEquals(JobDeployResponse.Status.JOB_NOT_FOUND, deployed3.getStatus());

    final JobDeployResponse deployed4 = client.deploy(deployment, BOGUS_AGENT).get();
    assertEquals(JobDeployResponse.Status.AGENT_NOT_FOUND, deployed4.getStatus());

    // undeploy and redeploy to make sure things still work in the face of the tombstone
    JobUndeployResponse undeployResp = client.undeploy(jobId, TEST_AGENT).get();
    assertEquals(JobUndeployResponse.Status.OK, undeployResp.getStatus());

    final JobDeployResponse redeployed = client.deploy(deployment, TEST_AGENT).get();
    assertEquals(JobDeployResponse.Status.OK, redeployed.getStatus());

    // Check that the job is in the desired state
    final Deployment fetchedDeployment = client.stat(TEST_AGENT, jobId).get();
    assertEquals(deployment, fetchedDeployment);

    // Wait for the job to run
    TaskStatus taskStatus;
    taskStatus = awaitJobState(client, TEST_AGENT, jobId, RUNNING, LONG_WAIT_MINUTES, MINUTES);
    assertEquals(job, taskStatus.getJob());

    assertEquals(JobDeleteResponse.Status.STILL_IN_USE, client.deleteJob(jobId).get().getStatus());

    // Wait for a while and make sure that the container is still running
    Thread.sleep(5000);
    final AgentStatus agentStatus = client.agentStatus(TEST_AGENT).get();
    taskStatus = agentStatus.getStatuses().get(jobId);
    assertEquals(RUNNING, taskStatus.getState());

    // Undeploy the job
    final JobUndeployResponse undeployed = client.undeploy(jobId, TEST_AGENT).get();
    assertEquals(JobUndeployResponse.Status.OK, undeployed.getStatus());

    // Make sure that it is no longer in the desired state
    final Deployment undeployedJob = client.stat(TEST_AGENT, jobId).get();
    assertNull(undeployedJob);

    // Wait for the task to disappear
    awaitTaskGone(client, TEST_AGENT, jobId, WAIT_TIMEOUT_SECONDS, SECONDS);

    // Verify that the job can be deleted
    assertEquals(JobDeleteResponse.Status.OK, client.deleteJob(jobId).get().getStatus());

    // Stop agent and verify that the agent status changes to DOWN
    agent.stopAsync().awaitTerminated();
    awaitAgentStatus(client, TEST_AGENT, DOWN, WAIT_TIMEOUT_SECONDS, SECONDS);
  }

  @Test
  public void testImagesWithPredefinedPortsAreDeployable() throws Exception {
    final String agentName = "foobar";
    startDefaultMaster();

    final Client client = defaultClient();

    startDefaultAgent(agentName);

    // Create a job using an image exposing port 80 but without mapping it
    final Job job1 = Job.newBuilder()
        .setName(PREFIX + "wordpress")
        .setVersion("v1")
        .setImage("jbfink/wordpress")
        .setCommand(DO_NOTHING_COMMAND)
        .build();
    final JobId jobId1 = job1.getId();
    client.createJob(job1).get();

    // Create a job using an image exposing port 80 and map it to 8080
    final Job job2 = Job.newBuilder()
        .setName(PREFIX + "wordpress")
        .setVersion("v2")
        .setImage("jbfink/wordpress")
        .setCommand(DO_NOTHING_COMMAND)
        .setPorts(ImmutableMap.of("tcp", PortMapping.of(80, 8080)))
        .build();
    final JobId jobId2 = job2.getId();
    client.createJob(job2).get();

    // Wait for agent to come up
    awaitAgentRegistered(client, agentName, WAIT_TIMEOUT_SECONDS, SECONDS);
    awaitAgentStatus(client, agentName, UP, WAIT_TIMEOUT_SECONDS, SECONDS);

    // Deploy the jobs on the agent
    client.deploy(Deployment.of(jobId1, START), agentName).get();
    client.deploy(Deployment.of(jobId2, START), agentName).get();

    // Wait for the jobs to run
    awaitJobState(client, agentName, jobId1, RUNNING, LONG_WAIT_MINUTES, MINUTES);
    awaitJobState(client, agentName, jobId2, RUNNING, LONG_WAIT_MINUTES, MINUTES);
  }

  private void startDefaultMaster() throws Exception {
    startMaster("-vvvv",
                "--no-log-setup",
                "--munin-port", "0",
                "--hm", masterEndpoint,
                "--name", masterName,
                "--zk", zookeeperEndpoint);
  }

  private AgentMain startDefaultAgent(final String agentName, final String... args)
      throws Exception {
    final List<String> argsList = Lists.newArrayList("-vvvv",
                                                     "--no-log-setup",
                                                     "--munin-port", "0",
                                                     "--name", agentName,
                                                     "--docker", DOCKER_ENDPOINT,
                                                     "--zk", zookeeperEndpoint,
//                                                     "--zk-session-timeout", "100",
                                                     "--state-dir", agentStateDir.toString());
    argsList.addAll(asList(args));
    return startAgent(argsList.toArray(new String[argsList.size()]));
  }

  private TaskStatus awaitJobState(final Client client, final String slave,
                                   final JobId jobId,
                                   final TaskStatus.State state, final int timeout,
                                   final TimeUnit timeunit) throws Exception {
    return await(timeout, timeunit, new Callable<TaskStatus>() {
      @Override
      public TaskStatus call() throws Exception {
        final AgentStatus agentStatus = client.agentStatus(slave).get();
        final TaskStatus taskStatus = agentStatus.getStatuses().get(jobId);
        return (taskStatus != null && taskStatus.getState() == state) ? taskStatus
                                                                      : null;
      }
    });
  }

  private TaskStatus awaitJobThrottle(final Client client, final String slave,
                                      final JobId jobId,
                                      final ThrottleState throttled, final int timeout,
                                      final TimeUnit timeunit) throws Exception {
    return await(timeout, timeunit, new Callable<TaskStatus>() {
      @Override
      public TaskStatus call() throws Exception {
        final AgentStatus agentStatus = client.agentStatus(slave).get();
        final TaskStatus taskStatus = agentStatus.getStatuses().get(jobId);
        return (taskStatus != null && taskStatus.getThrottled() == throttled) ? taskStatus : null;
      }
    });
  }

  private void awaitAgentRegistered(final Client client, final String slave,
                                    final int timeout,
                                    final TimeUnit timeUnit) throws Exception {
    await(timeout, timeUnit, new Callable<AgentStatus>() {
      @Override
      public AgentStatus call() throws Exception {
        return client.agentStatus(slave).get();
      }
    });
  }

  private AgentStatus awaitAgentStatus(final Client client, final String slave,
                                       final AgentStatus.Status status,
                                       final int timeout,
                                       final TimeUnit timeUnit) throws Exception {
    return await(timeout, timeUnit, new Callable<AgentStatus>() {
      @Override
      public AgentStatus call() throws Exception {
        final AgentStatus agentStatus = client.agentStatus(slave).get();
        if (agentStatus == null) {
          return null;
        }
        return (agentStatus.getStatus() == status) ? agentStatus : null;
      }
    });
  }

  private <T> T await(final long timeout, final TimeUnit timeUnit, final Callable<T> callable)
      throws Exception {
    final long deadline = nanoTime() + timeUnit.toNanos(timeout);
    while (nanoTime() < deadline) {
      final T value = callable.call();
      if (value != null) {
        return value;
      }
      Thread.sleep(500);
    }
    throw new TimeoutException();
  }

  @Test
  public void testServiceUsingCLI() throws Exception {
    startDefaultMaster();

    String output = control("master", "list");
    assertContains(masterName, output);

    assertContains("NOT_FOUND", deleteAgent(TEST_AGENT));

    startDefaultAgent(TEST_AGENT);

    final String image = "busybox";
    final Map<String, PortMapping> ports = ImmutableMap.of("foo", PortMapping.of(4711),
                                                           "bar", PortMapping.of(5000, 6000));
    final Map<ServiceEndpoint, ServicePorts> registration = ImmutableMap.of(
        ServiceEndpoint.of("foo-service", "hm"), ServicePorts.of("foo"),
        ServiceEndpoint.of("bar-service", "http"), ServicePorts.of("bar"));
    final Map<String, String> env = ImmutableMap.of("BAD", "f00d");

    // Wait for agent to come up
    awaitAgentRegistered(TEST_AGENT, WAIT_TIMEOUT_SECONDS, SECONDS);
    awaitAgentStatus(TEST_AGENT, UP, WAIT_TIMEOUT_SECONDS, SECONDS);

    // Create job
    final JobId jobId = createJob(JOB_NAME, JOB_VERSION, image, DO_NOTHING_COMMAND, env, ports,
                                  registration);

    // Query for job
    assertContains(jobId.toString(), control("job", "list", JOB_NAME, "-q"));
    assertContains(jobId.toString(), control("job", "list", JOB_NAME + ":" + JOB_VERSION, "-q"));
    assertTrue(control("job", "list", "foozbarz", "-q").trim().isEmpty());

    // Verify that port mapping and environment variables are correct
    final String statusString = control("job", "status", jobId.toString(), "--json");
    final Map<JobId, JobStatus> statuses = Json.read(statusString, STATUSES_TYPE);
    final Job job = statuses.get(jobId).getJob();
    assertEquals(ServicePorts.of("foo"),
                 job.getRegistration().get(ServiceEndpoint.of("foo-service", "hm")));
    assertEquals(ServicePorts.of("bar"),
                 job.getRegistration().get(ServiceEndpoint.of("bar-service", "http")));
    assertEquals(4711, job.getPorts().get("foo").getInternalPort());
    assertEquals(PortMapping.of(5000, 6000), job.getPorts().get("bar"));
    assertEquals("f00d", job.getEnv().get("BAD"));

    final String duplicateJob = control(
        "job", "create", JOB_NAME, JOB_VERSION, image, "--", DO_NOTHING_COMMAND);
    assertContains("JOB_ALREADY_EXISTS", duplicateJob);

    final String prestop = stopJob(jobId, TEST_AGENT);
    assertContains("JOB_NOT_DEPLOYED", prestop);

    // Deploy job
    deployJob(jobId, TEST_AGENT);

    // Stop job
    final String stop1 = stopJob(jobId, BOGUS_AGENT);
    assertContains("AGENT_NOT_FOUND", stop1);
    final String stop2 = stopJob(BOGUS_JOB, TEST_AGENT);
    assertContains("Unknown job", stop2);
    final String stop3 = stopJob(jobId, TEST_AGENT);
    assertContains(TEST_AGENT + ": done", stop3);

    // Undeploy job
    undeployJob(jobId, TEST_AGENT);

    assertContains(TEST_AGENT + ": done", deleteAgent(TEST_AGENT));
  }

  @Test
  public void testCreateJobWithConfigurationFile() throws Exception {
    startDefaultMaster();

    final Client client = defaultClient();

    final String name = "test";
    final String version = "17";
    final String image = "busybox";
    final Map<String, PortMapping> ports = ImmutableMap.of("foo", PortMapping.of(4711),
                                                           "bar", PortMapping.of(5000, 6000));
    final Map<ServiceEndpoint, ServicePorts> registration = ImmutableMap.of(
        ServiceEndpoint.of("foo-service", "hm"), ServicePorts.of("foo"),
        ServiceEndpoint.of("bar-service", "http"), ServicePorts.of("bar"));
    final Map<String, String> env = ImmutableMap.of("BAD", "f00d");

    final Map<String, Object> configuration = ImmutableMap.of("id", name + ":" + version,
                                                              "image", image,
                                                              "ports", ports,
                                                              "registration", registration,
                                                              "env", env);

    final Path file = Files.createTempFile("helios", ".json");
    file.toFile().delete();
    Files.write(file, Json.asBytes(configuration));

    final String output = control("job", "create", "-q", "-f", file.toAbsolutePath().toString());
    final JobId jobId = JobId.parse(StringUtils.strip(output));

    final Map<JobId, Job> jobs = client.jobs().get();
    final Job job = jobs.get(jobId);

    assertEquals(name, job.getId().getName());
    assertEquals(version, job.getId().getVersion());
    assertEquals(ports, job.getPorts());
    assertEquals(env, job.getEnv());
    assertEquals(registration, job.getRegistration());
  }


  private TaskStatus awaitTaskState(final JobId jobId, final String agent,
                                    final TaskStatus.State state) throws Exception {
    long timeout = WAIT_TIMEOUT_SECONDS;
    TimeUnit timeUnit = TimeUnit.SECONDS;
    return await(timeout, timeUnit, new Callable<TaskStatus>() {
      @Override
      public TaskStatus call() throws Exception {
        final String output = control("job", "status", "--json", jobId.toString());
        final Map<JobId, JobStatus> statusMap;
        try {
          statusMap = Json.read(output, new TypeReference<Map<JobId, JobStatus>>() {});
        } catch (IOException e) {
          return null;
        }
        final JobStatus status = statusMap.get(jobId);
        if (status == null) {
          return null;
        }
        final TaskStatus taskStatus = status.getTaskStatuses().get(agent);
        if (taskStatus == null) {
          return null;
        }
        if (taskStatus.getState() != state) {
          return null;
        }
        return taskStatus;
      }
    });
  }

  @Test
  public void testSyslogRedirection() throws Exception {
    // While this test doesn't specifically test that the output actually goes to syslog, it tests
    // just about every other part of it, and specifically, that the output doesn't get to
    // docker, and that the redirector executable exists and doesn't do anything terribly stupid.
    startDefaultMaster();
    startDefaultAgent(TEST_AGENT, "--syslog-redirect", "10.0.3.1:6514");
    awaitAgentStatus(TEST_AGENT, UP, WAIT_TIMEOUT_SECONDS, SECONDS);

    final DockerClient dockerClient = new DockerClient(DOCKER_ENDPOINT);

    final List<String> command = asList("sh", "-c", "echo should-be-redirected");

    // Create job
    final JobId jobId = createJob(JOB_NAME, JOB_VERSION, "ubuntu:12.04", command,
                                  ImmutableMap.of("FOO", "4711",
                                                  "BAR", "deadbeef"));

    // deploy
    deployJob(jobId, TEST_AGENT);

    final TaskStatus taskStatus = awaitTaskState(jobId, TEST_AGENT, EXITED);

    final ClientResponse response = dockerClient.logContainer(taskStatus.getContainerId());
    final String logMessage = readLogFully(response);
    // should be nothing in the docker output log, either error text or our message
    assertEquals("", logMessage);
  }

  @Test
  public void testContainerHostName() throws Exception {
    startDefaultMaster();
    startDefaultAgent(TEST_AGENT);
    awaitAgentStatus(TEST_AGENT, UP, WAIT_TIMEOUT_SECONDS, SECONDS);

    final DockerClient dockerClient = new DockerClient(DOCKER_ENDPOINT);

    final List<String> command = asList("hostname");

    // Create job
    final JobId jobId = createJob(JOB_NAME, JOB_VERSION, "busybox", command);

    // deploy
    deployJob(jobId, TEST_AGENT);

    final TaskStatus taskStatus = awaitTaskState(jobId, TEST_AGENT, EXITED);

    final ClientResponse response = dockerClient.logContainer(taskStatus.getContainerId());
    final String logMessage = readLogFully(response);

    assertContains(JOB_NAME + "_" + JOB_VERSION + "." + TEST_AGENT, logMessage);
  }

  @Test
  public void testEnvVariables() throws Exception {
    startDefaultMaster();
    startDefaultAgent(TEST_AGENT,
                      "--env",
                      "SPOTIFY_POD=PODNAME",
                      "SPOTIFY_ROLE=ROLENAME",
                      "BAR=badfood");
    awaitAgentStatus(TEST_AGENT, UP, WAIT_TIMEOUT_SECONDS, SECONDS);

    // Wait for the agent to report environment vars
    await(WAIT_TIMEOUT_SECONDS, SECONDS, new Callable<Object>() {
      @Override
      public Object call() throws Exception {
        Map<String, AgentStatus> status = Json.read(
            control("host", "status", TEST_AGENT, "--json"),
            new TypeReference<Map<String, AgentStatus>>() {});
        return status.get(TEST_AGENT).getEnvironment();
      }
    });

    final DockerClient dockerClient = new DockerClient(DOCKER_ENDPOINT);

    final List<String> command = asList("sh", "-c",
                                        "echo pod: $SPOTIFY_POD role: $SPOTIFY_ROLE foo: $FOO bar: $BAR");

    // Create job
    final JobId jobId = createJob(JOB_NAME, JOB_VERSION, "busybox", command,
                                  ImmutableMap.of("FOO", "4711",
                                                  "BAR", "deadbeef"));

    // deploy
    deployJob(jobId, TEST_AGENT);

    final TaskStatus taskStatus = awaitTaskState(jobId, TEST_AGENT, EXITED);

    final ClientResponse response = dockerClient.logContainer(taskStatus.getContainerId());
    final String logMessage = readLogFully(response);

    assertContains("pod: PODNAME", logMessage);
    assertContains("role: ROLENAME", logMessage);
    assertContains("foo: 4711", logMessage);

    // Verify that the the BAR environment variable in the job overrode the agent config
    assertContains("bar: deadbeef", logMessage);

    Map<String, AgentStatus> status = Json.read(control("host", "status", TEST_AGENT, "--json"),
                                                new TypeReference<Map<String, AgentStatus>>() {});

    assertEquals(ImmutableMap.of("SPOTIFY_POD", "PODNAME",
                                 "SPOTIFY_ROLE", "ROLENAME",
                                 "BAR", "badfood"),
                 status.get(TEST_AGENT).getEnvironment());

    assertEquals(ImmutableMap.of("SPOTIFY_POD", "PODNAME",
                                 "SPOTIFY_ROLE", "ROLENAME",
                                 "BAR", "deadbeef",
                                 "FOO", "4711"),
                 status.get(TEST_AGENT).getStatuses().get(jobId).getEnv());
  }

  private String readLogFully(final ClientResponse logs) throws IOException {
    final LogReader logReader = new LogReader(logs.getEntityInputStream());
    StringBuilder stringBuilder = new StringBuilder();
    LogReader.Frame frame;
    while ((frame = logReader.readFrame()) != null) {
      stringBuilder.append(UTF_8.decode(frame.getBytes()));
    }
    logReader.close();
    return stringBuilder.toString();
  }

  @Test
  public void testJobWatchExact() throws Exception {
    startDefaultMaster();
    startDefaultAgent(TEST_AGENT);
    awaitAgentStatus(TEST_AGENT, UP, WAIT_TIMEOUT_SECONDS, SECONDS);

    // Create job
    final JobId jobId = createJob(JOB_NAME, JOB_VERSION, "busybox", DO_NOTHING_COMMAND,
                                  ImmutableMap.of("FOO", "4711",
                                                  "BAR", "deadbeef"));

    // deploy
    deployJob(jobId, TEST_AGENT);

    final String[] commands = new String[]{"job", "watch", "--exact", "-z", masterEndpoint,
        "--no-log-setup", jobId.toString(), TEST_AGENT, "FAKE_TEST_AGENT"};

    final long now = System.currentTimeMillis();
    final AtomicBoolean success = new AtomicBoolean(false);
    final List<String> outputLines = Lists.newArrayList();

    final OutputStream out = new OutputStream() {
      private boolean seenKnownState = false;
      private boolean seenUnknownAgent = false;
      private int counter = 0;
      private final byte[] lineBuffer = new byte[8192];

      @Override
      public void write(int b) throws IOException {
        if (System.currentTimeMillis() - now > 10000) {
          throw new IOException("timed out trying to succeed");
        }
        lineBuffer[counter] = (byte) b;
        counter ++;

        if (b != 10) {
          return;
        }

        String line = Charsets.UTF_8.decode(
            ByteBuffer.wrap(lineBuffer, 0, counter)).toString();
        outputLines.add(line);
        counter = 0;

        if (line.contains(TEST_AGENT) && !line.contains("UNKNOWN")) {
          seenKnownState = true;
        }
        if (line.contains("FAKE_TEST_AGENT") && line.contains("UNKNOWN")) {
          seenUnknownAgent = true;
        }
        if (seenKnownState && seenUnknownAgent) {
          success.set(true);
          throw new IOException("output closed");
        }
      }
    };
    final CliMain main = new CliMain(new PrintStream(out),
       new PrintStream(new ByteArrayOutputStream()), commands);
    main.run();
    assertTrue("Should have stopped the stream due to success: got\n"
        + Joiner.on("").join(outputLines), success.get());
  }

  @Test
  public void testJobWatch() throws Exception {
    startDefaultMaster();
    startDefaultAgent(TEST_AGENT);
    awaitAgentStatus(TEST_AGENT, UP, WAIT_TIMEOUT_SECONDS, SECONDS);

    // Create job
    final JobId jobId = createJob(JOB_NAME, JOB_VERSION, "busybox", DO_NOTHING_COMMAND,
                                  ImmutableMap.of("FOO", "4711",
                                                  "BAR", "deadbeef"));

    // deploy
    deployJob(jobId, TEST_AGENT);

    final String[] commands = new String[]{"job", "watch", "-z", masterEndpoint,
        "--no-log-setup", jobId.toString()};

    final long now = System.currentTimeMillis();
    final AtomicBoolean success = new AtomicBoolean(false);
    final List<String> outputLines = Lists.newArrayList();
    final OutputStream out = new OutputStream() {
      private int counter = 0;
      private final byte[] lineBuffer = new byte[8192];

      @Override
      public void write(int b) throws IOException {
        if (System.currentTimeMillis() - now > 10000) {
          throw new IOException("timed out trying to succeed");
        }
        lineBuffer[counter] = (byte) b;
        counter ++;

        if (b != 10) {
          return;
        }

        String line = Charsets.UTF_8.decode(
            ByteBuffer.wrap(lineBuffer, 0, counter)).toString();
        outputLines.add(line);
        counter = 0;

        if (line.contains(TEST_AGENT) && !line.contains("UNKNOWN")) {
          success.set(true);
          throw new IOException("output closed");
        }
      }
    };
    final CliMain main = new CliMain(new PrintStream(out),
       new PrintStream(new ByteArrayOutputStream()), commands);
    main.run();
    assertTrue("Should have stopped the stream due to success: got\n"
        + Joiner.on("").join(outputLines), success.get());
  }

  /**
   * Verifies that:
   *
   * 1. The container is kept running when the agent is restarted.
   *
   * 2. A container that died while the agent was down is restarted when the agent comes up.
   *
   * 3. A container that was destroyed while the agent was down is restarted when the agent comes
   * up.
   *
   * 4. The container for a job that was undeployed while the agent was down is killed when the
   * agent comes up again.
   */
  @Test
  public void testAgentRestart() throws Exception {
    startDefaultMaster();

    final DockerClient dockerClient = new DockerClient(DOCKER_ENDPOINT);

    final Client client = defaultClient();

    final AgentMain agent1 = startDefaultAgent(TEST_AGENT);

    // A simple netcat echo server
    final List<String> command =
        asList("bash", "-c",
               "DEBIAN_FRONTEND=noninteractive " +
               "apt-get install -q -y --force-yes nmap && " +
               "ncat -l -p 4711 -c \"while true; do read i && echo $i; done\"");

    // TODO (dano): connect to the server during the test and verify that the connection is never broken

    // Create a job
    final Job job = Job.newBuilder()
        .setName(JOB_NAME)
        .setVersion(JOB_VERSION)
        .setImage("ubuntu:12.04")
        .setCommand(command)
        .build();
    final JobId jobId = job.getId();
    final CreateJobResponse created = client.createJob(job).get();
    assertEquals(CreateJobResponse.Status.OK, created.getStatus());

    // Wait for agent to come up
    awaitAgentRegistered(client, TEST_AGENT, WAIT_TIMEOUT_SECONDS, SECONDS);
    awaitAgentStatus(client, TEST_AGENT, UP, WAIT_TIMEOUT_SECONDS, SECONDS);

    // Deploy the job on the agent
    final Deployment deployment = Deployment.of(jobId, START);
    final JobDeployResponse deployed = client.deploy(deployment, TEST_AGENT).get();
    assertEquals(JobDeployResponse.Status.OK, deployed.getStatus());

    // Wait for the job to run
    final TaskStatus firstTaskStatus = awaitJobState(client, TEST_AGENT, jobId, RUNNING,
                                                     LONG_WAIT_MINUTES, MINUTES);
    assertEquals(job, firstTaskStatus.getJob());
    assertEquals(1, listContainers(dockerClient, PREFIX).size());

    // Stop the agent
    agent1.stopAsync().awaitTerminated();
    awaitAgentStatus(client, TEST_AGENT, DOWN, WAIT_TIMEOUT_SECONDS, SECONDS);

    // Start the agent again
    final AgentMain agent2 = startDefaultAgent(TEST_AGENT);
    awaitAgentStatus(client, TEST_AGENT, UP, WAIT_TIMEOUT_SECONDS, SECONDS);

    // Wait for a while and make sure that the same container is still running
    Thread.sleep(5000);
    final AgentStatus agentStatus = client.agentStatus(TEST_AGENT).get();
    final TaskStatus taskStatus = agentStatus.getStatuses().get(jobId);
    assertEquals(RUNNING, taskStatus.getState());
    assertEquals(firstTaskStatus.getContainerId(), taskStatus.getContainerId());

    // Stop the agent
    agent2.stopAsync().awaitTerminated();
    awaitAgentStatus(client, TEST_AGENT, DOWN, WAIT_TIMEOUT_SECONDS, SECONDS);

    // Kill the container
    dockerClient.stopContainer(firstTaskStatus.getContainerId());
    assertEquals(0, listContainers(dockerClient, PREFIX).size());

    // Start the agent again
    final AgentMain agent3 = startDefaultAgent(TEST_AGENT);
    awaitAgentStatus(client, TEST_AGENT, UP, WAIT_TIMEOUT_SECONDS, SECONDS);

    // Wait for the job to be restarted in a new container
    final TaskStatus secondTaskStatus = await(
        LONG_WAIT_MINUTES, MINUTES,
        new Callable<TaskStatus>() {
          @Override
          public TaskStatus call() throws Exception {
            final AgentStatus agentStatus = client.agentStatus(TEST_AGENT).get();
            final TaskStatus taskStatus = agentStatus.getStatuses().get(jobId);
            return (taskStatus != null && taskStatus.getContainerId() != null &&
                    taskStatus.getState() == RUNNING &&
                    !taskStatus.getContainerId().equals(firstTaskStatus.getContainerId()))
                   ? taskStatus
                   : null;
          }
        });
    assertEquals(1, listContainers(dockerClient, PREFIX).size());

    // Stop the agent
    agent3.stopAsync().awaitTerminated();
    awaitAgentStatus(client, TEST_AGENT, DOWN, WAIT_TIMEOUT_SECONDS, SECONDS);

    // Kill and destroy the container
    dockerClient.stopContainer(secondTaskStatus.getContainerId());
    dockerClient.removeContainer(secondTaskStatus.getContainerId());
    try {
      // This should fail with an exception if the container still exists
      dockerClient.inspectContainer(secondTaskStatus.getContainerId());
      fail();
    } catch (DockerException ignore) {
    }

    // Start the agent again
    final AgentMain agent4 = startDefaultAgent(TEST_AGENT);
    awaitAgentStatus(client, TEST_AGENT, UP, WAIT_TIMEOUT_SECONDS, SECONDS);

    // Wait for the task to be restarted in a new container
    await(LONG_WAIT_MINUTES, MINUTES, new Callable<TaskStatus>() {
      @Override
      public TaskStatus call() throws Exception {
        final AgentStatus agentStatus = client.agentStatus(TEST_AGENT).get();
        final TaskStatus taskStatus = agentStatus.getStatuses().get(jobId);
        return (taskStatus != null && taskStatus.getContainerId() != null &&
                taskStatus.getState() == RUNNING &&
                !taskStatus.getContainerId().equals(secondTaskStatus.getContainerId())) ? taskStatus
                                                                                        : null;
      }
    });
    assertEquals(1, listContainers(dockerClient, PREFIX).size());

    // Stop the agent
    agent4.stopAsync().awaitTerminated();
    awaitAgentStatus(client, TEST_AGENT, DOWN, WAIT_TIMEOUT_SECONDS, SECONDS);

    // Stop the job
    final SetGoalResponse stopped = client.setGoal(Deployment.of(jobId, STOP), TEST_AGENT).get();
    assertEquals(SetGoalResponse.Status.OK, stopped.getStatus());

    // Start the agent again
    final AgentMain agent5 = startDefaultAgent(TEST_AGENT);
    awaitAgentStatus(client, TEST_AGENT, UP, WAIT_TIMEOUT_SECONDS, SECONDS);

    // Verify that the task is stopped
    awaitJobState(client, TEST_AGENT, jobId, STOPPED, WAIT_TIMEOUT_SECONDS, SECONDS);
    assertEquals(0, listContainers(dockerClient, PREFIX).size());

    // Stop the agent
    agent5.stopAsync().awaitTerminated();
    awaitAgentStatus(client, TEST_AGENT, DOWN, WAIT_TIMEOUT_SECONDS, SECONDS);

    // Start the job
    final SetGoalResponse started = client.setGoal(Deployment.of(jobId, START), TEST_AGENT).get();
    assertEquals(SetGoalResponse.Status.OK, started.getStatus());

    // Start the agent again
    final AgentMain agent6 = startDefaultAgent(TEST_AGENT);
    awaitAgentStatus(client, TEST_AGENT, UP, WAIT_TIMEOUT_SECONDS, SECONDS);

    // Verify that the task is started
    awaitJobState(client, TEST_AGENT, jobId, RUNNING, WAIT_TIMEOUT_SECONDS, SECONDS);
    assertEquals(1, listContainers(dockerClient, PREFIX).size());

    // Stop the agent
    agent6.stopAsync().awaitTerminated();
    awaitAgentStatus(client, TEST_AGENT, DOWN, WAIT_TIMEOUT_SECONDS, SECONDS);

    // Undeploy the job
    final JobUndeployResponse undeployed = client.undeploy(jobId, TEST_AGENT).get();
    assertEquals(JobUndeployResponse.Status.OK, undeployed.getStatus());

    // Start the agent again
    startDefaultAgent(TEST_AGENT);
    awaitAgentStatus(client, TEST_AGENT, UP, WAIT_TIMEOUT_SECONDS, SECONDS);

    // Wait for the task to get removed
    awaitTaskGone(client, TEST_AGENT, jobId, WAIT_TIMEOUT_SECONDS, SECONDS);
    assertEquals(0, listContainers(dockerClient, PREFIX).size());
  }

  @Test
  public void testAgentToleratesZooKeeperDown() throws Exception {
    startDefaultMaster();

    final DockerClient dockerClient = new DockerClient(DOCKER_ENDPOINT);

    final Client client = defaultClient();

    final AgentMain agent1 = startDefaultAgent(TEST_AGENT);

    // A simple netcat echo server
    final List<String> command =
        asList("bash", "-c",
               "DEBIAN_FRONTEND=noninteractive " +
               "apt-get install -q -y --force-yes nmap && " +
               "ncat -l -p 4711 -c \"while true; do read i && echo $i; done\"");

    // Create a job
    final Job job = Job.newBuilder()
        .setName(JOB_NAME)
        .setVersion(JOB_VERSION)
        .setImage("ubuntu:12.04")
        .setCommand(command)
        .build();
    final JobId jobId = job.getId();
    final CreateJobResponse created = client.createJob(job).get();
    assertEquals(CreateJobResponse.Status.OK, created.getStatus());

    // Wait for agent to come up
    awaitAgentRegistered(client, TEST_AGENT, WAIT_TIMEOUT_SECONDS, SECONDS);
    awaitAgentStatus(client, TEST_AGENT, UP, WAIT_TIMEOUT_SECONDS, SECONDS);

    // Deploy the job on the agent
    final Deployment deployment = Deployment.of(jobId, START);
    final JobDeployResponse deployed = client.deploy(deployment, TEST_AGENT).get();
    assertEquals(JobDeployResponse.Status.OK, deployed.getStatus());

    // Wait for the job to run
    final TaskStatus firstTaskStatus = awaitJobState(client, TEST_AGENT, jobId, RUNNING,
                                                     LONG_WAIT_MINUTES, MINUTES);
    assertEquals(job, firstTaskStatus.getJob());
    assertNotNull(dockerClient.inspectContainer(firstTaskStatus.getContainerId()));

    // Stop zookeeper
    stopZookeeper();

    // Wait for a while and make sure that the container is still running
    Thread.sleep(5000);
    assertNotNull(dockerClient.inspectContainer(firstTaskStatus.getContainerId()));

    // Stop the agent
    agent1.stopAsync().awaitTerminated();

    // Start the agent again
    final AgentMain agent2 = startDefaultAgent(TEST_AGENT);

    // Wait for a while and make sure that the same container is still running
    Thread.sleep(5000);
    assertNotNull(dockerClient.inspectContainer(firstTaskStatus.getContainerId()));

    // Kill the container
    dockerClient.stopContainer(firstTaskStatus.getContainerId());
    assertEquals(0, listContainers(dockerClient, PREFIX).size());

    // Wait for a while and make sure that a new container was spawned
    Thread.sleep(5000);
    assertNotNull(dockerClient.inspectContainer(firstTaskStatus.getContainerId()));
    final List<Container> containers1 = listContainers(dockerClient, PREFIX);
    assertEquals(1, containers1.size());
    final String firstRestartedContainerId = containers1.get(0).id;

    // Stop the agent
    agent2.stopAsync().awaitTerminated();

    // Kill the container
    dockerClient.stopContainer(firstRestartedContainerId);
    assertEquals(0, listContainers(dockerClient, PREFIX).size());

    // Start the agent again
    startDefaultAgent(TEST_AGENT);

    // Wait for a while and make sure that a new container was spawned
    Thread.sleep(5000);
    assertNotNull(dockerClient.inspectContainer(firstTaskStatus.getContainerId()));
    final List<Container> containers2 = listContainers(dockerClient, PREFIX);
    assertEquals(1, containers2.size());
    final String secondRestartedContainerId = containers2.get(0).id;

    // Start zookeeper
    startZookeeper();

    // Verify that the agent is listed as up
    awaitAgentStatus(client, TEST_AGENT, UP, WAIT_TIMEOUT_SECONDS, SECONDS);

    // Wait for the new container id to be reflected in the task status
    await(WAIT_TIMEOUT_SECONDS, SECONDS, new Callable<TaskStatus>() {
      @Override
      public TaskStatus call() throws Exception {
        final JobStatus jobStatus = client.jobStatus(jobId).get();
        final TaskStatus taskStatus = jobStatus.getTaskStatuses().get(TEST_AGENT);
        return taskStatus != null && Objects.equals(taskStatus.getContainerId(),
                                                    secondRestartedContainerId)
               ? taskStatus : null;
      }
    });
  }

  private void awaitTaskGone(final Client client, final String host, final JobId jobId,
                             final long timeout, final TimeUnit timeunit) throws Exception {
    await(timeout, timeunit, new Callable<Boolean>() {
      @Override
      public Boolean call() throws Exception {
        final AgentStatus agentStatus = client.agentStatus(host).get();
        final TaskStatus taskStatus = agentStatus.getStatuses().get(jobId);
        return taskStatus == null ? true : null;
      }
    });
  }

  private List<Container> listContainers(final DockerClient dockerClient, final String needle) {
    final List<Container> containers = dockerClient.listContainers(false);
    final List<Container> matches = Lists.newArrayList();
    for (final Container container : containers) {
      for (final String name : container.names) {
        if (name.contains(needle)) {
          matches.add(container);
          break;
        }
      }
    }
    return matches;
  }

  private String stopJob(final JobId jobId, final String agent) throws Exception {
    return control("job", "stop", jobId.toString(), agent);
  }

  private String deleteAgent(final String testAgent) throws Exception {
    return control("host", "deregister", testAgent, "--force");
  }

  private JobId createJob(final String name, final String version, final String image,
                          final List<String> command) throws Exception {
    return createJob(name, version, image, command, EMPTY_ENV,
                     new HashMap<String, PortMapping>(), null);
  }

  private JobId createJob(final String name, final String version, final String image,
                          final List<String> command, final ImmutableMap<String, String> env)
      throws Exception {
    return createJob(name, version, image, command, env, new HashMap<String, PortMapping>(), null);
  }

  private JobId createJob(final String name, final String version, final String image,
                          final List<String> command, final Map<String, String> env,
                          final Map<String, PortMapping> ports) throws Exception {
    return createJob(name, version, image, command, env, ports, null);
  }

  private JobId createJob(final String name, final String version, final String image,
                          final List<String> command, final Map<String, String> env,
                          final Map<String, PortMapping> ports,
                          final Map<ServiceEndpoint, ServicePorts> registration)
  throws Exception {
    checkArgument(name.contains(PREFIX), "Job name must contain PREFIX to enable cleanup");

    final List<String> args = Lists.newArrayList("-q", name, version, image);

    if (!env.isEmpty()) {
      args.add("--env");
      for (final Map.Entry<String, String> entry : env.entrySet()) {
        args.add(entry.getKey() + "=" + entry.getValue());
      }
    }

    if (!ports.isEmpty()) {
      args.add("--port");
      for (final Map.Entry<String, PortMapping> entry : ports.entrySet()) {
        String value = "" + entry.getValue().getInternalPort();
        if (entry.getValue().getExternalPort() != null) {
          value += ":" + entry.getValue().getExternalPort();
        }
        if (entry.getValue().getProtocol() != null) {
          value += "/" + entry.getValue().getProtocol();
        }
        args.add(entry.getKey() + "=" + value);
      }
    }

    if (registration != null) {
      for (final Map.Entry<ServiceEndpoint, ServicePorts> entry : registration.entrySet()) {
        final ServiceEndpoint r = entry.getKey();
        for (String portName : entry.getValue().getPorts().keySet()) {
          args.add("--register=" + ((r.getProtocol() == null)
                                    ? format("%s=%s", r.getName(), portName)
                                    : format("%s/%s=%s", r.getName(), r.getProtocol(), portName)));
        }
      }
    }

    args.add("--");
    args.addAll(command);

    final String createOutput = control("job", "create", args);
    final String jobId = StringUtils.strip(createOutput);

    final String listOutput = control("job", "list", "-q");
    assertContains(jobId, listOutput);
    return JobId.fromString(jobId);
  }
}
