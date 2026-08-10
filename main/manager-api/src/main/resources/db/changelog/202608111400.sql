ALTER TABLE `ai_mobile_event`
    DROP CHECK `chk_mobile_event_state`,
    ADD CONSTRAINT `chk_mobile_event_state`
        CHECK (`event_state` IN ('posted','updated','removed','entered','exited','dwelled'));
