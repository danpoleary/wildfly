/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.test.smoke.messaging.client.messaging;

import static org.junit.Assert.assertNotNull;

import javax.resource.spi.IllegalStateException;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.hornetq.api.core.HornetQException;
import org.hornetq.api.core.SimpleString;
import org.hornetq.api.core.TransportConfiguration;
import org.hornetq.api.core.client.ClientConsumer;
import org.hornetq.api.core.client.ClientMessage;
import org.hornetq.api.core.client.ClientProducer;
import org.hornetq.api.core.client.ClientSession;
import org.hornetq.api.core.client.ClientSession.QueueQuery;
import org.hornetq.api.core.client.ClientSessionFactory;
import org.hornetq.api.core.client.HornetQClient;
import org.hornetq.core.remoting.impl.netty.NettyConnectorFactory;
import org.hornetq.core.remoting.impl.netty.TransportConstants;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.as.arquillian.api.ContainerResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.OperationBuilder;
import org.jboss.as.test.integration.common.jms.JMSOperationsProvider;
import org.jboss.dmr.ModelNode;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Demo using the AS management API to create and destroy a HornetQ core queue.
 *
 * @author Emanuel Muckenhuber
 * @author Kabir Khan
 */
@RunWith(Arquillian.class)
@RunAsClient
public class MessagingClientTestCase {

    @ContainerResource
    private ManagementClient managementClient;

    @Before
    public void before() {
        Assume.assumeTrue("Test is relevant only when the messaging subsystem with HornetQ is setup",
            "hornetq".equals(JMSOperationsProvider.getInstance(managementClient).getProviderName()));
    }

    @Ignore
    @Test
    public void testMessagingClientUsingMessagingPort() throws Exception {
        final ClientSessionFactory sf = createClientSessionFactory(managementClient.getWebUri().getHost(), 5445, false);
        doMessagingClient(sf);
        sf.close();
    }

    @Test
    public void testMessagingClientUsingHTTPPort() throws Exception {
        final ClientSessionFactory sf = createClientSessionFactory(managementClient.getWebUri().getHost(), managementClient.getWebUri().getPort(), true);
        doMessagingClient(sf);
        sf.close();
    }

    public void loop() throws Exception {
        for (int i = 0; i < 1000; i++) {
            System.out.println("i = " + i);
            testMessagingClientUsingHTTPPort();
        }
    }

    private void doMessagingClient(ClientSessionFactory sf) throws Exception {
        final String queueName = "queue.standalone";

        final ModelControllerClient client = managementClient.getControllerClient();

        // Check that the queue does not exists
        if (queueExists(queueName, sf)) {
            throw new IllegalStateException();
        }

        // Create a new core queue using the standalone client
        ModelNode op = new ModelNode();
        op.get("operation").set("add");
        op.get("address").add("subsystem", "messaging");
        op.get("address").add("hornetq-server", "default");
        op.get("address").add("queue", queueName);
        op.get("queue-address").set(queueName);
        applyUpdate(op, client);
        // Check if the queue exists
        if (!queueExists(queueName, sf)) {
            throw new IllegalStateException();
        }

        ClientSession session = null;
        try {
            session = sf.createSession("guest", "guest", false, true, true, false, 1);
            ClientProducer producer = session.createProducer(queueName);
            ClientMessage message = session.createMessage(false);

            final String propName = "myprop";
            message.putStringProperty(propName, "Hello sent at " + new Date());

            producer.send(message);

            ClientConsumer messageConsumer = session.createConsumer(queueName);
            session.start();

            ClientMessage messageReceived = messageConsumer.receive(1000);
            assertNotNull("a message MUST have been received", messageReceived);
        } finally {
            if (session != null) {
                session.close();
            }
        }

        op = new ModelNode();
        op.get("operation").set("remove");
        op.get("address").add("subsystem", "messaging");
        op.get("address").add("hornetq-server", "default");
        op.get("address").add("queue", queueName);
        applyUpdate(op, client);

        // Check that the queue does not exists
        if(queueExists(queueName, sf)) {
            throw new IllegalStateException();
        }
    }

    static void applyUpdate(ModelNode update, final ModelControllerClient client) throws IOException {
        ModelNode result = client.execute(new OperationBuilder(update).build());
        if (result.hasDefined("outcome") && "success".equals(result.get("outcome").asString())) {
            if (result.hasDefined("result")) {
                System.out.println(result.get("result"));
            }
        } else if (result.hasDefined("failure-description")) {
            throw new RuntimeException(result.get("failure-description").toString());
        } else {
            throw new RuntimeException("Operation not successful; outcome = " + result.get("outcome"));
        }
    }

    static boolean queueExists(final String queueName, final ClientSessionFactory sf) throws HornetQException {
        final ClientSession session = sf.createSession("guest", "guest", false, false, false, false, 1);
        try {
            final QueueQuery query = session.queueQuery(new SimpleString(queueName));
            return query.isExists();
        } finally {
            session.close();
        }
    }

    static ClientSessionFactory createClientSessionFactory(String host, int port, boolean httpUpgradeEnabled) throws Exception {
        final Map<String, Object> properties = new HashMap<String, Object>();
        properties.put(TransportConstants.HOST_PROP_NAME, host);
        properties.put(TransportConstants.PORT_PROP_NAME, port);
        properties.put(TransportConstants.HTTP_UPGRADE_ENABLED_PROP_NAME, httpUpgradeEnabled);
        if (httpUpgradeEnabled) {
            properties.put(TransportConstants.HTTP_UPGRADE_ENDPOINT_PROP_NAME, "http-acceptor");
        }
        final TransportConfiguration configuration = new TransportConfiguration(NettyConnectorFactory.class.getName(), properties);
        return HornetQClient.createServerLocatorWithoutHA(configuration).createSessionFactory();
    }

}
