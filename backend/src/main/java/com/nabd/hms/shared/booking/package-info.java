/** COM-044: what a patient-channel booking needs from treatment packages ('Book session N of M'), without scheduling depending on the
 * packages module. Implemented by packages; called by scheduling (queue) and the Edge household read (patient). */
@org.springframework.modulith.NamedInterface("booking")
package com.nabd.hms.shared.booking;
