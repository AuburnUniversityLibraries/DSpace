/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */

package org.dspace.app.requestitem;

import java.io.IOException;
import java.sql.SQLException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import jakarta.annotation.ManagedBean;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.mail.MessagingException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.app.requestitem.service.RequestItemService;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Item;
import org.dspace.content.service.BitstreamService;
import org.dspace.core.Context;
import org.dspace.core.Email;
import org.dspace.core.I18nUtil;
import org.dspace.core.LogHelper;
import org.dspace.core.Utils;
import org.dspace.eperson.EPerson;
import org.dspace.handle.service.HandleService;
import org.dspace.services.ConfigurationService;

/**
 * Send item requests and responses by email.
 *
 * <p>The "strategy" by which approvers are chosen is in an implementation of
 * {@link RequestItemAuthorExtractor} which is injected by the name
 * {@code requestItemAuthorExtractor}.  See the DI configuration documents.
 *
 * @author Mark H. Wood <mwood@iupui.edu>
 */
@Singleton
@ManagedBean
public class RequestItemEmailNotifier {
    private static final Logger LOG = LogManager.getLogger();

    @Inject
    protected BitstreamService bitstreamService;

    @Inject
    protected ConfigurationService configurationService;

    @Inject
    protected HandleService handleService;

    @Inject
    protected RequestItemService requestItemService;

    protected final RequestItemAuthorExtractor requestItemAuthorExtractor;

    @Inject
    public RequestItemEmailNotifier(RequestItemAuthorExtractor requestItemAuthorExtractor) {
        this.requestItemAuthorExtractor = requestItemAuthorExtractor;
    }

    /**
     * Send the request to the approver(s).
     *
     * @param context current DSpace session.
     * @param ri the request.
     * @param responseLink link back to DSpace to send the response.
     * @throws IOException passed through.
     * @throws SQLException if the message was not sent.
     */
    public void sendRequest(Context context, RequestItem ri, String responseLink)
            throws IOException, SQLException {
        // Who is making this request?
        List<RequestItemAuthor> authors = requestItemAuthorExtractor
                .getRequestItemAuthor(context, ri.getItem());

        // Build an email to the approver.
        Email email = Email.getEmail(I18nUtil.getEmailFilename(context.getCurrentLocale(),
                "request_item.author"));
        for (RequestItemAuthor author : authors) {
            email.addRecipient(author.getEmail());
        }
        email.setReplyTo(ri.getReqEmail()); // Requester's address

        email.addArgument(ri.getReqName()); // {0} Requester's name

        email.addArgument(ri.getReqEmail()); // {1} Requester's address

        email.addArgument(ri.isAllfiles() // {2} All bitstreams or just one?
            ? I18nUtil.getMessage("itemRequest.all") : ri.getBitstream().getName());

        email.addArgument(handleService.getCanonicalForm(ri.getItem().getHandle())); // {3}

        email.addArgument(ri.getItem().getName()); // {4} requested item's title

        email.addArgument(ri.getReqMessage()); // {5} message from requester

        email.addArgument(responseLink); // {6} Link back to DSpace for action

        StringBuilder names = new StringBuilder();
        StringBuilder addresses = new StringBuilder();
        for (RequestItemAuthor author : authors) {
            if (names.length() > 0) {
                names.append("; ");
                addresses.append("; ");
            }
            names.append(author.getFullName());
            addresses.append(author.getEmail());
        }
        email.addArgument(names.toString()); // {7} corresponding author name
        email.addArgument(addresses.toString()); // {8} corresponding author email

        email.addArgument(configurationService.getProperty("dspace.name")); // {9}

        email.addArgument(configurationService.getProperty("mail.helpdesk")); // {10}

        // Send the email.
        try {
            email.send();
            Bitstream bitstream = ri.getBitstream();
            String bitstreamID;
            if (null == bitstream) {
                bitstreamID = "null";
            } else {
                bitstreamID = ri.getBitstream().getID().toString();
            }
            LOG.info(LogHelper.getHeader(context,
                    "sent_email_requestItem",
                    "submitter_id={},bitstream_id={},requestEmail={}"),
                    ri.getReqEmail(), bitstreamID, ri.getReqEmail());
        } catch (MessagingException e) {
            LOG.warn(LogHelper.getHeader(context,
                    "error_mailing_requestItem", e.getMessage()));
            throw new IOException("Request not sent:  " + e.getMessage());
        }
    }

    /**
     * Send the approver's response back to the requester, with files attached
     * if approved.
     *
     * @param context current DSpace session.
     * @param ri the request.
     * @param subject email subject header value.
     * @param message email body (may be empty).
     * @throws IOException if sending failed.
     */
    public void sendResponse(Context context, RequestItem ri, String subject,
            String message)
            throws IOException {
        // Who granted this request?
        List<RequestItemAuthor> grantors;
        try {
            grantors = requestItemAuthorExtractor.getRequestItemAuthor(context, ri.getItem());
        } catch (SQLException e) {
            LOG.warn("Failed to get grantor's name and address:  {}", e.getMessage());
            grantors = List.of();
        }

        String grantorName;
        String grantorAddress;
        if (grantors.isEmpty()) {
            grantorName = configurationService.getProperty("mail.admin.name");
            grantorAddress = configurationService.getProperty("mail.admin");
        } else {
            RequestItemAuthor grantor = grantors.get(0); // XXX Cannot know which one
            grantorName = grantor.getFullName();
            grantorAddress = grantor.getEmail();
        }

        // Set date format for access expiry date
        String accessExpiryFormat = configurationService.getProperty("request.item.grant.link.dateformat",
                "yyyy-MM-dd");
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(accessExpiryFormat)
                .withZone(ZoneId.of("UTC"));

        Email email;
        // If this item has a secure access token, send the template with that link instead of attaching files
        if (ri.isAccept_request() && ri.getAccess_token() != null) {
            email = Email.getEmail(I18nUtil.getEmailFilename(context.getCurrentLocale(),
                    "request_item.granted_token"));
        } else {
            email = Email.getEmail(I18nUtil.getEmailFilename(context.getCurrentLocale(),
                    ri.isAccept_request() ? "request_item.granted" : "request_item.rejected"));
        }

        // Build an email back to the requester.
        email.addArgument(ri.getReqName()); // {0} requestor's name
        email.addArgument(handleService.getCanonicalForm(ri.getItem().getHandle())); // {1} URL of the requested Item
        email.addArgument(ri.getItem().getName()); // {2} title of the requested Item
        email.addArgument(grantorName);     // {3} name of the grantor
        email.addArgument(grantorAddress);  // {4} email of the grantor
        email.addArgument(message); //         {5} grantor's optional message
        email.setSubject(subject);
        email.addRecipient(ri.getReqEmail());
        // Attach bitstreams.
        try {
            if (ri.isAccept_request()) {
                if (ri.getAccess_token() != null) {
                    // {6} secure access link
                    email.addArgument(configurationService.getProperty("dspace.ui.url")
                            + "/items/" + ri.getItem().getID()
                            + "?accessToken=" + ri.getAccess_token());
                    // {7} access end date, but only add formatted date string if it is set and not "forever"
                    if (ri.getAccess_expiry() != null && !ri.getAccess_expiry().equals(Utils.getMaxTimestamp())) {
                        email.addArgument(dateTimeFormatter.format(ri.getAccess_expiry()));
                    } else {
                        email.addArgument(null);
                    }
                } else {
                    if (ri.isAllfiles()) {
                        Item item = ri.getItem();
                        List<Bundle> bundles = item.getBundles("ORIGINAL");
                        for (Bundle bundle : bundles) {
                            List<Bitstream> bitstreams = bundle.getBitstreams();
                            for (Bitstream bitstream : bitstreams) {
                                if (!bitstream.getFormat(context).isInternal() &&
                                        requestItemService.isRestricted(context,
                                                bitstream)) {
                                    // #8636 Anyone receiving the email can respond to the
                                    // request without authenticating into DSpace
                                    context.turnOffAuthorisationSystem();
                                    email.addAttachment(
                                            bitstreamService.retrieve(context, bitstream),
                                            bitstream.getName(),
                                            bitstream.getFormat(context).getMIMEType());
                                    context.restoreAuthSystemState();
                                }
                            }
                        }
                    } else {
                        Bitstream bitstream = ri.getBitstream();
                        //#8636 Anyone receiving the email can respond to the request without authenticating into DSpace
                        context.turnOffAuthorisationSystem();
                        email.addAttachment(bitstreamService.retrieve(context, bitstream),
                                bitstream.getName(),
                                bitstream.getFormat(context).getMIMEType());
                        context.restoreAuthSystemState();
                    }
                }
                email.send();
            } else {
                boolean sendRejectEmail = configurationService
                    .getBooleanProperty("request.item.reject.email", true);
                // Not all sites want the "refusal" to be sent back to the requester via
                // email. However, by default, the rejection email is sent back.
                if (sendRejectEmail) {
                    email.send();
                }
            }
        } catch (MessagingException | IOException | SQLException | AuthorizeException e) {
            LOG.warn(LogHelper.getHeader(context,
                    "error_mailing_requestItem", e.getMessage()));
            throw new IOException("Reply not sent:  " + e.getMessage());
        }
        LOG.info(LogHelper.getHeader(context,
                "sent_attach_requestItem", "token={}"), ri.getToken());
    }

    /**
     * Send, to a repository administrator, a request to open access to a
     * requested object.
     *
     * @param context current DSpace session
     * @param ri the item request that the approver is handling
     * @throws IOException if the message body cannot be loaded or the message
     *          cannot be sent.
     */
    public void requestOpenAccess(Context context, RequestItem ri)
            throws IOException {
        Email message = Email.getEmail(I18nUtil.getEmailFilename(context.getCurrentLocale(),
                "request_item.admin"));

        // Which Bitstream(s) requested?
        Bitstream bitstream = ri.getBitstream();
        String bitstreamName;
        if (bitstream != null) {
            bitstreamName = bitstream.getName();
        } else {
            bitstreamName = "all"; // TODO localize
        }

        // Which Item?
        Item item = ri.getItem();

        // Fill the message's placeholders.
        EPerson approver = context.getCurrentUser();
        message.addArgument(bitstreamName);          // {0} bitstream name or "all"
        message.addArgument(item.getHandle());       // {1} Item handle
        message.addArgument(ri.getToken());          // {2} Request token
        if (approver != null) {
            message.addArgument(approver.getFullName()); // {3} Approver's name
            message.addArgument(approver.getEmail());    // {4} Approver's address
        } else {
            message.addArgument("anonymous approver");                           // [3] Approver's name
            message.addArgument(configurationService.getProperty("mail.admin")); // [4] Approver's address
        }

        // Who gets this message?
        String recipient;
        EPerson submitter = item.getSubmitter();
        if (submitter != null) {
            recipient = submitter.getEmail();
        } else {
            recipient = configurationService.getProperty("mail.helpdesk");
        }
        if (null == recipient) {
            recipient = configurationService.getProperty("mail.admin");
        }
        message.addRecipient(recipient);

        // Send the message.
        try {
            message.send();
        } catch (MessagingException ex) {
            LOG.warn(LogHelper.getHeader(context, "error_mailing_requestItem",
                    ex.getMessage()));
            throw new IOException("Open Access request not sent:  " + ex.getMessage());
        }
    }
}
