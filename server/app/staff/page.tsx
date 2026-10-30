import { redirect } from "next/navigation";

export default function StaffIndex(): never {
  redirect("/staff/overview");
}
