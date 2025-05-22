package fi.csc.chipster.scheduler.resource;

import java.util.HashMap;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.scheduler.Scheduler;
import fi.csc.chipster.scheduler.bash.BashJobScheduler;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Scheduler Rest API
 * 
 * Scheduler doesn't need this API for its primary work. It follows Job events
 * from session-db and starts new Job containers.
 * 
 * This API provides information about the public scheduler configuration for
 * the app and comp. This allows these limits to be configured only in one
 * place.
 */
@Path("quotas")
public class SchedulerResource {

    private static final String KEY_DEFAULT_SLOTS = "default-slots";
    public static final String KEY_DEFAULT_STORAGE = "default-storage";
    private static final String KEY_SLOT_MEMORY = "slot-memory";
    private static final String KEY_SLOT_CPU = "slot-cpu";
    private static final String KEY_MAX_STORAGE = "max-storage";
    private static final String KEY_MAX_SLOTS = "max-slots";
    private Config config;

    public SchedulerResource(Config config) {
        this.config = config;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public final Response get() {

        HashMap<String, Object> quotas = new HashMap<>();

        quotas.put(KEY_MAX_SLOTS,
                config.getLong(Scheduler.CONF_MAX_SCHEDULED_AND_RUNNING_SLOTS_PER_USER));
        quotas.put(KEY_MAX_STORAGE,
                config.getLong(Scheduler.CONF_MAX_SCHEDULED_AND_RUNNING_STORAGE_PER_USER));

        quotas.put(KEY_SLOT_CPU, config.getLong(BashJobScheduler.CONF_BASH_SLOT_CPU));
        quotas.put(KEY_SLOT_MEMORY, config.getLong(BashJobScheduler.CONF_BASH_SLOT_MEMORY));
        quotas.put(KEY_DEFAULT_STORAGE, config.getLong(Scheduler.CONF_DEFAULT_STORAGE_PER_JOB));
        quotas.put(KEY_DEFAULT_SLOTS, 1);

        return Response.ok(quotas).build();
    }
}
