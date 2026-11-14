package ru.eventpass.app

/** OS verified links are deferred for remote. Demo links belong only to demo. */
internal fun acceptVariantLink(value: String?): Boolean = value?.startsWith("eventpass-demo://demo/") == true
