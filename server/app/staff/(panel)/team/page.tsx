import { permanentRedirect } from "next/navigation";
import { requireStaffContext } from "@/lib/staff/current";

/** Team management moved into the profile; old links keep working. */
export default async function TeamPage(): Promise<never> {
  await requireStaffContext();
  return permanentRedirect("/staff/profile#team");
}
