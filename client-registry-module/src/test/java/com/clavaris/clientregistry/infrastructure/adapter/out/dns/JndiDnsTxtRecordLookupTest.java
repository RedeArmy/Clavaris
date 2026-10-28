package com.clavaris.clientregistry.infrastructure.adapter.out.dns;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * TD-PERF-008, made hermetic by TD-TEST-010. The first version of this test asked the real DNS for
 * {@code _dmarc.google.com}; it failed full builds twice when the sandbox's resolver answered
 * SERVFAIL for a couple of seconds (and a retry did not help), which says nothing about this class.
 * It now queries a one-socket DNS server running in the test, so the same lookup code (JNDI's DNS
 * provider, the TXT parsing, the failure handling) is exercised with answers the test controls,
 * including the failures a real resolver produces.
 */
class JndiDnsTxtRecordLookupTest {

  private static final int NOERROR = 0;
  private static final int SERVFAIL = 2;
  private static final int NXDOMAIN = 3;

  private final Map<String, Answer> answers = new ConcurrentHashMap<>();
  private DatagramSocket server;
  private Thread serverThread;
  private JndiDnsTxtRecordLookup lookup;

  /** What the stub says for one name: a response code and the TXT strings to return. */
  private record Answer(int responseCode, List<String> texts) {}

  @BeforeEach
  void startStubDnsServer() throws IOException {
    server = new DatagramSocket(0, InetAddress.getLoopbackAddress());
    serverThread = new Thread(this::serve, "stub-dns");
    serverThread.setDaemon(true);
    serverThread.start();
    lookup = new JndiDnsTxtRecordLookup("dns://127.0.0.1:" + server.getLocalPort());
  }

  @AfterEach
  void stopStubDnsServer() {
    server.close();
  }

  private void serve() {
    final byte[] buffer = new byte[512];
    while (!server.isClosed()) {
      final DatagramPacket query = new DatagramPacket(buffer, buffer.length);
      try {
        server.receive(query);
        final byte[] response = respondTo(query.getData());
        server.send(new DatagramPacket(response, response.length, query.getSocketAddress()));
      } catch (final IOException _) {
        return; // socket closed by the test
      }
    }
  }

  // The question starts at byte 12: labels, a zero byte, then QTYPE and QCLASS (4 bytes).
  private byte[] respondTo(final byte[] query) throws IOException {
    int end = 12;
    final StringBuilder name = new StringBuilder();
    while (query[end] != 0) {
      final int labelLength = query[end] & 0xFF;
      name.append(new String(query, end + 1, labelLength, StandardCharsets.US_ASCII)).append('.');
      end += labelLength + 1;
    }
    end += 5;
    final Answer answer = answers.getOrDefault(name.toString(), new Answer(NXDOMAIN, List.of()));
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(query, 0, 2); // the id the client is waiting for
    out.write(0x81);
    out.write(0x80 | answer.responseCode());
    out.write(new byte[] {0, 1, 0, (byte) answer.texts().size(), 0, 0, 0, 0});
    out.write(query, 12, end - 12); // the question, echoed
    for (final String text : answer.texts()) {
      final byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
      out.write(new byte[] {(byte) 0xC0, 0x0C, 0, 16, 0, 1, 0, 0, 0, 60});
      out.write(new byte[] {0, (byte) (bytes.length + 1), (byte) bytes.length});
      out.write(bytes);
    }
    return out.toByteArray();
  }

  @Test
  void returnsTheTxtValuesOfARecordWithTheQuotesStripped() {
    answers.put(
        "_clavaris-verify.example.test.", new Answer(NOERROR, List.of("v=DMARC1; p=reject")));

    assertThat(lookup.lookupTxtRecords("_clavaris-verify.example.test"))
        .containsExactly("v=DMARC1; p=reject");
  }

  @Test
  void returnsEveryValueWhenARecordHasSeveral() {
    answers.put(
        "challenge.example.test.", new Answer(NOERROR, List.of("clavaris-token=abc", "other=1")));

    assertThat(lookup.lookupTxtRecords("challenge.example.test"))
        .containsExactlyInAnyOrder("clavaris-token=abc", "other=1");
  }

  // RFC 2606 section 2: ".invalid" never resolves.
  @Test
  void returnsAnEmptyListRatherThanThrowingForANonExistentDomain() {
    assertThat(lookup.lookupTxtRecords("this-domain-does-not-exist-clavaris-test.invalid"))
        .isEmpty();
  }

  // What a failing real resolver answers: it must read as "no record", never as an exception.
  @Test
  void returnsAnEmptyListWhenTheResolverFails() {
    answers.put("broken.example.test.", new Answer(SERVFAIL, List.of()));

    assertThat(lookup.lookupTxtRecords("broken.example.test")).isEmpty();
  }

  @Test
  void returnsAnEmptyListWhenTheNameExistsButHasNoTxtRecord() {
    answers.put("quiet.example.test.", new Answer(NOERROR, List.of()));

    assertThat(lookup.lookupTxtRecords("quiet.example.test")).isEmpty();
  }
}
