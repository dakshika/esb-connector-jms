/*
* Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.wso2.carbon.esb.connector;

import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMNode;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axiom.om.OMText;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.transport.MessageFormatter;
//import org.apache.axis2.transport.base.BaseConstants;
import org.apache.axis2.transport.base.BaseUtils;
import org.apache.axis2.transport.jms.JMSUtils;
import org.apache.axis2.transport.jms.iowrappers.BytesMessageOutputStream;
import org.apache.axis2.util.MessageProcessorSelector;
import org.apache.commons.io.output.WriterOutputStream;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.activation.DataHandler;
import javax.jms.*;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.transaction.UserTransaction;
import java.io.*;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Properties;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This class maintains all the JMS sessions and connections required to publish a message to a single topic/queue.
 */
public class PublisherContext {

    private static final Log log = LogFactory.getLog(PublisherContext.class);
    /**
     * Properties read from the above file.
     */
    private static Properties jndiProperties;
    /**
     *
     */
    private final String connectionFactoryValue;
    /**
     * Object-wise lock to synchronize publishing to the same topic.
     */
    private final Lock publisherLock = new ReentrantLock();
    /**
     * Connection Factory type specific to message broker
     */
    private String namingFactory;
    /**
     * Name of destination.
     */
    private String destinationName;
    /**
     * Name of connection factory.
     */
    private String connectionFactoryName;
    /**
     * "queue" or "topic"
     */
    private String destinationType;
    /**
     * JMS Connection Factory used to publish to the topic/queue.
     */
    private ConnectionFactory connectionFactory;
    /**
     * Network connection used to communicate with message broker.
     */
    private Connection connection;
    /**
     * JMS Destination object as lookup from JNDI context.
     */
    private Destination destination;
    /**
     * JMS Session used to communicate with message broker.
     */
    private Session session;

    /**
     * Message Producer used within the above JMS session.
     */
    private MessageProducer messageProducer;

    /**
     * Initialize the PublisherContext for a specific destination planning to use a pre-defined JMS connection factory.
     *
     * @param destinationName        Name of topic
     * @param connectionFactoryName  Name of JMS connection factory as defined in jndi.properties file.
     * @param connectionFactoryValue URL of the JNDI provider.
     * @throws NamingException if the jndi processing results in an invalid naming convention or non-existent
     *                         properties.
     * @throws JMSException    Connectivity issues, invalid destination type
     */
    public PublisherContext(String destinationName, String connectionFactoryName, String destinationType,
                            String connectionFactoryValue, String namingFactory)
            throws JMSException, NamingException {
        this.destinationName = destinationName;
        this.connectionFactoryName = connectionFactoryName;
        this.destinationType = destinationType;
        this.connectionFactoryValue = connectionFactoryValue;
        this.namingFactory = namingFactory;
        if (null == jndiProperties) {
            initializeJNDIProperties();
        }
        switch (destinationType) {
            case JMSConnectorConstants.QUEUE_NAME_PREFIX:
                initializeQueueProducer();
                break;
            case JMSConnectorConstants.TOPIC_NAME_PREFIX:
                initializeTopicProducer();
                break;
            default:
                throw new JMSException("Invalid destination type. It must be a queue or a topic. " +
                        "Current value : " + destinationType);
        }
    }

    /**
     * Set a property within the input message context.
     *
     * @param message JMS Message
     * @param msgCtx  Message context
     * @param key     key for the property
     */
    private static void setProperty(Message message, MessageContext msgCtx, String key) {
        String value = getProperty(msgCtx, key);
        if (value != null) {
            try {
                message.setStringProperty(key, value);
            } catch (JMSException e) {
                log.warn("Couldn't set message property : " + key + " = " + value, e);
            }
        }
    }

    /**
     * Utility method to direct any exceptions to the ESB mediation engine.
     *
     * @param msg description of error
     * @param e   Exception
     * @throws AxisFault
     */
    private static void handleException(String msg, Exception e) throws AxisFault {
        log.error(msg, e);
        throw new AxisFault(msg, e);
    }

    /**
     * Read a property from the synapse message context.
     *
     * @param mc  message context
     * @param key key
     * @return Value of property
     */
    private static String getProperty(MessageContext mc, String key) {
        return (String) mc.getProperty(key);
    }

    /**
     * Read an integer property from the message context.
     *
     * @param msgCtx message context
     * @param name   key of property
     * @return value of property
     */
    private static Integer getIntegerProperty(MessageContext msgCtx, String name) {
        Object o = msgCtx.getProperty(name);
        if (o != null) {
            if (o instanceof Integer) {
                return (Integer) o;
            } else if (o instanceof String) {
                return Integer.parseInt((String) o);
            }
        }
        return null;
    }

    /**
     * Read a boolean property from the message context
     *
     * @param msgCtx message context
     * @param name   key of property
     * @return value of property
     */
    private static Boolean getBooleanProperty(MessageContext msgCtx, String name) {
        Object o = msgCtx.getProperty(name);
        if (o != null) {
            if (o instanceof Boolean) {
                return (Boolean) o;
            } else if (o instanceof String) {
                return Boolean.valueOf((String) o);
            }
        }
        return null;
    }

    /**
     * Create the JNDI properties for the JMS communication within the connector.
     *
     * @throws NamingException
     * @throws IOException
     */
    private void initializeJNDIProperties() {
        jndiProperties = new Properties();
        jndiProperties.put("connectionfactory." + connectionFactoryName, connectionFactoryValue);
        jndiProperties.put(Context.INITIAL_CONTEXT_FACTORY, namingFactory);
    }

    /**
     * @throws NamingException
     * @throws JMSException
     */
    private void initializeQueueProducer() throws NamingException, JMSException {
        if (!jndiProperties.containsKey(JMSConnectorConstants.QUEUE_NAME_PREFIX + "." + destinationName)) {
            log.warn("Queue not defined in default jndi.properties !");
            jndiProperties.put(JMSConnectorConstants.QUEUE_NAME_PREFIX + "." + destinationName, destinationName);
        }
        InitialContext initialJMSContext = new InitialContext(jndiProperties);
        connectionFactory = (QueueConnectionFactory) initialJMSContext.lookup(connectionFactoryName);
        connection = ((QueueConnectionFactory) connectionFactory).createQueueConnection();
        String contextKey = destinationType + ":/" + destinationName;
        connection.setExceptionListener(new JMSExceptionListener(contextKey));
        session = ((QueueConnection) connection).createQueueSession(false, QueueSession.AUTO_ACKNOWLEDGE);
        Queue queue = (Queue) initialJMSContext.lookup(destinationName);
        messageProducer = ((QueueSession) session).createSender(queue);
        destination = queue;
    }

    /**
     * @throws NamingException
     * @throws JMSException
     */
    private void initializeTopicProducer() throws NamingException, JMSException {
        if (!jndiProperties.containsKey(JMSConnectorConstants.TOPIC_NAME_PREFIX + "." + destinationName)) {
            log.warn("Topic not defined in default jndi.properties !");
            jndiProperties.put(JMSConnectorConstants.TOPIC_NAME_PREFIX + "." + destinationName, destinationName);
        }
        InitialContext initialJMSContext = new InitialContext(jndiProperties);
        connectionFactory = (TopicConnectionFactory) initialJMSContext.lookup(connectionFactoryName);
        connection = ((TopicConnectionFactory) connectionFactory).createTopicConnection();
        String contextKey = destinationType + ":/" + destinationName;
        connection.setExceptionListener(new JMSExceptionListener(contextKey));
        session = ((TopicConnection) connection).createTopicSession(false, TopicSession.AUTO_ACKNOWLEDGE);
        Topic topic = (Topic) initialJMSContext.lookup(destinationName);
        messageProducer = ((TopicSession) session).createPublisher(topic);
        destination = topic;
    }

    /**
     * Method exposed to publish a message using this JMS context (session, connection).
     *
     * @param messageContext synapse message context
     * @throws AxisFault
     * @throws JMSException
     */
    public void publishMessage(MessageContext messageContext) throws AxisFault, JMSException {
        if (null != session && null != messageProducer) {
            Message messageToPublish = createJMSMessage(messageContext);
            send(messageToPublish, messageContext);
        }
    }

    /**
     * Create a JMS Message from the given MessageContext and using the given session
     *
     * @param msgContext the MessageContext
     * @return a JMS message from the context and session
     * @throws JMSException               on exception
     * @throws org.apache.axis2.AxisFault on exception
     */
    private Message createJMSMessage(MessageContext msgContext) throws JMSException, AxisFault {
        Message message = null;
        String msgType = getProperty(msgContext, JMSConnectorConstants.JMS_MESSAGE_TYPE);

        // check the first element of the SOAP body, do we have content wrapped using the
        // default wrapper elements for binary (BaseConstants.DEFAULT_BINARY_WRAPPER) or
        // text (BaseConstants.DEFAULT_TEXT_WRAPPER) ? If so, do not create SOAP messages
        // for JMS but just get the payload in its native format
        String jmsPayloadType = guessMessageType(msgContext);

        if (jmsPayloadType == null) {
            OMOutputFormat format = BaseUtils.getOMOutputFormat(msgContext);
            MessageFormatter messageFormatter;
            try {
                messageFormatter = MessageProcessorSelector.getMessageFormatter(msgContext);
            } catch (AxisFault axisFault) {
                throw new JMSException("Unable to get the message formatter to use");
            }
            String contentType = messageFormatter.getContentType(msgContext, format, msgContext.getSoapAction());
            boolean useBytesMessage = msgType != null && JMSConnectorConstants.JMS_BYTE_MESSAGE.equals(msgType) ||
                    contentType.contains(JMSConnectorConstants.HEADER_ACCEPT_MULTIPART_RELATED);
            OutputStream out;
            StringWriter sw;
            if (useBytesMessage) {
                BytesMessage bytesMsg = session.createBytesMessage();
                sw = null;
                out = new BytesMessageOutputStream(bytesMsg);
                message = bytesMsg;
            } else {
                sw = new StringWriter();
                try {
                    out = new WriterOutputStream(sw, format.getCharSetEncoding());
                } catch (UnsupportedCharsetException ex) {
                    handleException("Unsupported encoding " + format.getCharSetEncoding(), ex);
                    return null;
                }
            }
            try {
                messageFormatter.writeTo(msgContext, format, out, true);
                out.close();
            } catch (IOException e) {
                handleException("IO Error while creating BytesMessage", e);
            }
            if (!useBytesMessage) {
                TextMessage txtMsg = session.createTextMessage();
                txtMsg.setText(sw.toString());
                message = txtMsg;
            }
        } else if (JMSConnectorConstants.JMS_BYTE_MESSAGE.equals(jmsPayloadType)) {
            message = session.createBytesMessage();
            BytesMessage bytesMsg = (BytesMessage) message;
            OMElement wrapper = msgContext.getEnvelope().getBody().
                    getFirstChildWithName(JMSConnectorConstants.DEFAULT_BINARY_WRAPPER);
            OMNode omNode = wrapper.getFirstOMChild();
            if (omNode != null && omNode instanceof OMText) {
                Object dh = ((OMText) omNode).getDataHandler();
                if (dh != null && dh instanceof DataHandler) {
                    try {
                        ((DataHandler) dh).writeTo(new BytesMessageOutputStream(bytesMsg));
                    } catch (IOException e) {
                        handleException("Error serializing binary content of element : " +
                                JMSConnectorConstants.DEFAULT_BINARY_WRAPPER, e);
                    }
                }
            }
        } else if (JMSConnectorConstants.JMS_TEXT_MESSAGE.equals(jmsPayloadType)) {
            message = session.createTextMessage();
            TextMessage txtMsg = (TextMessage) message;
            txtMsg.setText(msgContext.getEnvelope().getBody().
                    getFirstChildWithName(JMSConnectorConstants.DEFAULT_TEXT_WRAPPER).getText());
        } else if (JMSConnectorConstants.JMS_MAP_MESSAGE.equalsIgnoreCase(jmsPayloadType)) {
            message = session.createMapMessage();
            JMSUtils.convertXMLtoJMSMap(msgContext.getEnvelope().getBody().getFirstChildWithName(
                    JMSConnectorConstants.JMS_MAP_QNAME), (MapMessage) message);
        }
        // set the JMS correlation ID if specified
        String correlationId = getProperty(msgContext, JMSConnectorConstants.JMS_COORELATION_ID);
        if (correlationId == null && msgContext.getRelatesTo() != null) {
            correlationId = msgContext.getRelatesTo().getValue();
        }
        if (correlationId != null) {
            assert message != null;
            message.setJMSCorrelationID(correlationId);
        }
        if (msgContext.isServerSide()) {
            // set SOAP Action as a property on the JMS message
            setProperty(message, msgContext, JMSConnectorConstants.SOAPACTION);
        } else {
            String action = msgContext.getOptions().getAction();
            if (action != null) {
                assert message != null;
                message.setStringProperty(JMSConnectorConstants.SOAPACTION, action);
            }
        }
        JMSUtils.setTransportHeaders(msgContext, message);
        return message;
    }

    /**
     * Guess the message type to use for JMS looking at the message contexts' envelope
     *
     * @param msgContext the message context
     * @return JMSConnectorConstants.JMS_BYTE_MESSAGE or JMSConnectorConstants.JMS_TEXT_MESSAGE or null
     */
    private String guessMessageType(MessageContext msgContext) {
        OMElement firstChild = msgContext.getEnvelope().getBody().getFirstElement();
        if (firstChild != null) {
            if (JMSConnectorConstants.DEFAULT_BINARY_WRAPPER.equals(firstChild.getQName())) {
                return JMSConnectorConstants.JMS_BYTE_MESSAGE;
            } else if (JMSConnectorConstants.DEFAULT_TEXT_WRAPPER.equals(firstChild.getQName())) {
                return JMSConnectorConstants.JMS_TEXT_MESSAGE;
            } else if (JMSConnectorConstants.JMS_MAP_QNAME.equals(firstChild.getQName())) {
                return JMSConnectorConstants.JMS_MAP_MESSAGE;
            }
        }
        return null;
    }

    /**
     * Perform actual send of JMS message to the Destination selected
     *
     * @param message the JMS message
     * @param msgCtx  the Axis2 MessageContext
     */
    private void send(Message message, MessageContext msgCtx) throws AxisFault {
        publisherLock.lock();
        Boolean jtaCommit = getBooleanProperty(msgCtx, JMSConnectorConstants.JTA_COMMIT_AFTER_SEND);
        Boolean persistent = getBooleanProperty(msgCtx, JMSConnectorConstants.JMS_DELIVERY_MODE);
        Integer priority = getIntegerProperty(msgCtx, JMSConnectorConstants.JMS_PRIORITY);
        Integer timeToLive = getIntegerProperty(msgCtx, JMSConnectorConstants.JMS_TIME_TO_LIVE);
        if (persistent != null) {
            try {
                messageProducer.setDeliveryMode(DeliveryMode.PERSISTENT);
            } catch (JMSException e) {
                handleException("Error setting JMS Producer for PERSISTENT delivery", e);
            }
        }
        if (priority != null) {
            try {
                messageProducer.setPriority(priority);
            } catch (JMSException e) {
                handleException("Error setting JMS Producer priority to : " + priority, e);
            }
        }
        if (timeToLive != null) {
            try {
                messageProducer.setTimeToLive(timeToLive);
            } catch (JMSException e) {
                handleException("Error setting JMS Producer TTL to : " + timeToLive, e);
            }
        }
        boolean sendingSuccessful = false;
        // perform actual message sending
        try {
            if (JMSConnectorConstants.QUEUE_NAME_PREFIX.equals(destinationType)) {
                try {
                    ((QueueSender) messageProducer).send(message);
                } catch (JMSException e) {
                    //create a queue reference in MB before publishing.
                    ((QueueSession) session).createQueue(destinationName);
                    ((QueueSender) messageProducer).send(message);
                }
            } else {
                ((TopicPublisher) messageProducer).publish(message);
            }
            if (log.isDebugEnabled()) {
                log.debug("Published message to " + destinationType + " : " + destinationName);
            }
            // set the actual MessageID to the message context for use by any others down the line
            String msgId = null;
            try {
                msgId = message.getJMSMessageID();
                if (msgId != null) {
                    msgCtx.setProperty(JMSConnectorConstants.JMS_MESSAGE_ID, msgId);
                }
            } catch (JMSException ignore) {
            }
            sendingSuccessful = true;
            if (log.isDebugEnabled()) {
                log.debug("Sent Message Context ID : " + msgCtx.getMessageID() + " with JMS Message ID : " + msgId
                        + " to destination : " + messageProducer.getDestination());
            }
        } catch (JMSException e) {
            handleException("Error sending message with MessageContext ID : " + msgCtx.getMessageID()
                    + " to destination " + destinationType + " : " + destinationName, e);
        } finally {
            if (jtaCommit != null) {
                UserTransaction userTransaction = (UserTransaction) msgCtx.getProperty(JMSConnectorConstants.USER_TRANSACTION);
                if (userTransaction != null) {
                    try {
                        if (sendingSuccessful && jtaCommit) {
                            userTransaction.commit();
                        } else {
                            userTransaction.rollback();
                        }
                        msgCtx.removeProperty(JMSConnectorConstants.USER_TRANSACTION);
                        if (log.isDebugEnabled()) {
                            log.debug((sendingSuccessful ? "Committed" : "Rolled back") +
                                    " JTA Transaction");
                        }
                    } catch (Exception e) {
                        handleException("Error committing/rolling back JTA transaction after " +
                                "sending of message with MessageContext ID : " + msgCtx.getMessageID() +
                                " to destination : " + destinationName, e);
                    }
                }
            } else {
                try {
                    if (session.getTransacted()) {
                        if (sendingSuccessful) {
                            session.commit();
                        } else {
                            session.rollback();
                        }
                    }
                    if (log.isDebugEnabled()) {
                        log.debug((sendingSuccessful ? "Committed" : "Rolled back") +
                                " local (JMS Session) Transaction");
                    }
                } catch (JMSException e) {
                    handleException("Error committing/rolling back local (i.e. session) " +
                            "transaction after sending of message with MessageContext ID : " +
                            msgCtx.getMessageID() + " to destination : " + destinationName, e);
                }
            }
            publisherLock.unlock();
        }
    }

    /**
     * Method to properly shutdown the JMS sessions and connections in the proper order. This is normally called when
     * a cached publisherContext expires.
     *
     * @throws JMSException
     */
    public void close() throws JMSException {
        if (null != messageProducer) {
            messageProducer.close();
        }
        if (null != session) {
            session.close();
        }
        if (null != connection) {
            connection.close();
        }
        if (null != connectionFactory) {
            connectionFactory = null;
        }
    }

    @Override
    /**
     *
     */
    public boolean equals(Object obj) {
        if (obj instanceof PublisherContext) {
            if (((PublisherContext) obj).publisherLock == this.publisherLock)
                return true;
        }
        return false;
    }

    @Override
    /**
     * In case cache expiry does not happen, the GC collection should trigger the shutdown of the context.
     */
    protected void finalize() throws Throwable {
        close();
        super.finalize();
    }
}